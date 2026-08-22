package io.paradaux.hibernia.framework.usher;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Singleton;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.annotations.Action;
import io.paradaux.hibernia.framework.usher.annotations.ActionArg;
import io.paradaux.hibernia.framework.usher.annotations.Dialog;
import io.paradaux.hibernia.framework.usher.annotations.Input;
import io.paradaux.hibernia.framework.usher.annotations.Model;
import io.paradaux.hibernia.framework.usher.annotations.Screen;
import io.paradaux.hibernia.framework.usher.binders.BuiltinInputBinders;
import io.paradaux.hibernia.framework.usher.render.DialogRenderer;
import io.paradaux.hibernia.framework.usher.spi.BedrockSupport;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import io.paradaux.hibernia.framework.usher.spi.InputBinder;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Orchestrates dialog handlers — the dialog-tier analogue of
 * {@link io.paradaux.hibernia.framework.commander.CommandManager}.
 *
 * <p>It indexes {@link Dialog @Dialog} handlers (their {@link Screen @Screen} and {@link Action @Action}
 * methods) at construction, opens a {@link DialogFlow} for a player on demand, renders each screen's
 * {@link DialogView} through a {@link DialogRenderer}, and routes button clicks back to {@code @Action}
 * methods with typed {@link Input @Input} values resolved by {@link InputBinder}s.</p>
 *
 * <p>The Paper dialog runtime is touched only through the injected {@link DialogRenderer}; everything
 * else is plain data, which is why the dialog tier is unit-testable without a server.</p>
 *
 * <p>Error handling mirrors command dispatch: the framework's HTTP-semantic exceptions thrown from an
 * action render to the viewer through the same {@code hibernia.error.*} message keys; anything else logs
 * a stack trace and shows a generic message.</p>
 */
@Singleton
@Slf4j
public class DialogManager {

    private static final String DEFAULT_SCREEN = "main";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String DEFAULT_NO_PERMISSION = "<red>You don't have permission to do that.</red>";
    private static final String DEFAULT_WITH_MESSAGE = "<red>{message}</red>";
    private static final String DEFAULT_INTERNAL =
            "<red>An internal error occurred. Please contact an administrator.</red>";

    private final JavaPlugin plugin;
    private final DialogRenderer renderer;
    private final Map<Class<?>, InputBinder<?>> binders = new ConcurrentHashMap<>();
    private final Map<Class<?>, HandlerModel> handlers = new LinkedHashMap<>();
    private final BedrockSupport bedrock;
    private final Message message;

    /**
     * Non-Guice constructor (tests, manual wiring). Error messages use built-in MiniMessage defaults and
     * everyone is treated as a Java player.
     */
    public DialogManager(JavaPlugin plugin, Set<DialogHandler> handlerBeans,
                         Set<InputBinder<?>> binderSet, DialogRenderer renderer) {
        this(plugin, handlerBeans, binderSet, renderer, null);
    }

    @Inject
    public DialogManager(JavaPlugin plugin, Set<DialogHandler> handlerBeans,
                         Set<InputBinder<?>> binderSet, DialogRenderer renderer, Injector injector) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.bedrock = resolve(injector, BedrockSupport.class, BedrockSupport.NONE);
        this.message = resolve(injector, Message.class, null);

        binderSet.forEach(b -> binders.put(b.type(), b));
        BuiltinInputBinders.all().forEach(b -> binders.putIfAbsent(b.type(), b));

        handlerBeans.stream()
                .sorted(Comparator.comparing(h -> h.getClass().getName()))
                .forEach(this::indexHandler);
    }

    /** Open a handler's default screen for {@code viewer}, carrying {@code model}. */
    public DialogFlow open(Player viewer, Class<? extends DialogHandler> handler, Object model) {
        HandlerModel hm = requireHandler(handler);
        return open(viewer, handler, hm.defaultScreen, model);
    }

    /** Open a specific screen of a handler for {@code viewer}, carrying {@code model}. */
    public DialogFlow open(Player viewer, Class<? extends DialogHandler> handler, String screen, Object model) {
        requireHandler(handler);
        DialogFlow flow = new DialogFlow(this, viewer, handler, model, bedrock.isBedrock(viewer));
        flow.open(screen);
        return flow;
    }

    // ── package-private hooks used by DialogFlow ──────────────────────────────────

    void renderScreen(DialogFlow flow, String screenName) {
        HandlerModel hm = requireHandler(flow.handlerType());
        Method method = hm.screens.get(screenName);
        if (method == null) {
            throw new IllegalArgumentException("Dialog " + hm.type.getSimpleName()
                    + " has no @Screen named '" + screenName + "'");
        }
        DialogView view;
        try {
            Object[] args = injectParams(hm, method, flow, null);
            view = (DialogView) method.invoke(hm.instance, args);
        } catch (InvocationTargetException ite) {
            renderError(flow.player(), ite.getTargetException(), hm, "screen " + screenName);
            return;
        } catch (Exception e) {
            renderError(flow.player(), e, hm, "screen " + screenName);
            return;
        }
        if (view == null) {
            log.warn("@Screen {}#{} returned null", hm.type.getSimpleName(), screenName);
            return;
        }
        show(flow, view);
    }

    void showWait(DialogFlow flow, Text waitText) {
        show(flow, DialogView.notice(waitText)
                .canCloseWithEscape(true)
                .afterAction(DialogView.AfterAction.NONE)
                .build());
    }

    void closeFor(DialogFlow flow) {
        renderer.close(flow.player());
    }

    void runMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    void handleAsyncError(DialogFlow flow, Throwable error) {
        renderError(flow.player(), error, requireHandler(flow.handlerType()), "async task");
    }

    // ── rendering ─────────────────────────────────────────────────────────────────

    private void show(DialogFlow flow, DialogView view) {
        Player viewer = flow.player();
        renderer.show(viewer, view, text -> resolveText(viewer, text), button -> callbackFor(flow, button));
    }

    private Component resolveText(Player viewer, Text text) {
        if (text instanceof Text.Literal literal) {
            return literal.component();
        }
        Text.Keyed keyed = (Text.Keyed) text;
        if (message == null) {
            // No Message bean: treat the key as raw MiniMessage so prototyping still renders.
            return MINI.deserialize(keyed.key());
        }
        Locale locale = viewer != null ? viewer.locale() : null;
        return locale != null
                ? message.component(locale, keyed.key(), keyed.placeholders())
                : message.component(keyed.key(), keyed.placeholders());
    }

    private DialogActionCallback callbackFor(DialogFlow flow, ButtonSpec button) {
        return (view, audience) -> {
            try {
                switch (button.kind()) {
                    case CLOSE -> flow.close();
                    case BACK -> flow.back();
                    case OPEN -> flow.open(button.target());
                    case ACTION -> dispatchAction(flow, button.target(), button.argument(), view);
                }
            } catch (Exception e) {
                renderError(flow.player(), e, requireHandler(flow.handlerType()), "button " + button.kind());
            }
        };
    }

    private void dispatchAction(DialogFlow flow, String actionName, String argument,
                                DialogResponseView view) {
        HandlerModel hm = requireHandler(flow.handlerType());
        Method method = hm.actions.get(actionName);
        if (method == null) {
            log.warn("Dialog {} has no @Action named '{}'", hm.type.getSimpleName(), actionName);
            renderError(flow.player(), null, hm, "missing action " + actionName);
            return;
        }
        DialogContext ctx = new DialogContext(view, flow);
        try {
            Object[] args = injectParams(hm, method, flow, ctx, argument);
            method.invoke(hm.instance, args);
        } catch (InvocationTargetException ite) {
            renderError(flow.player(), ite.getTargetException(), hm, "action " + actionName);
        } catch (Exception e) {
            renderError(flow.player(), e, hm, "action " + actionName);
        }
    }

    // ── parameter injection ───────────────────────────────────────────────────────

    private Object[] injectParams(HandlerModel hm, Method method, DialogFlow flow, DialogContext ctx) {
        return injectParams(hm, method, flow, ctx, null);
    }

    private Object[] injectParams(HandlerModel hm, Method method, DialogFlow flow, DialogContext ctx,
                                  String argument) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = injectParam(hm, method, params[i], flow, ctx, argument);
        }
        return args;
    }

    private Object injectParam(HandlerModel hm, Method method, Parameter param, DialogFlow flow,
                               DialogContext ctx, String argument) {
        if (param.isAnnotationPresent(ActionArg.class)) {
            if (ctx == null) {
                throw new IllegalStateException("@ActionArg parameter is only valid on an @Action ("
                        + method + ")");
            }
            return coerceActionArg(argument, param.getType(), method);
        }
        Input input = param.getAnnotation(Input.class);
        if (input != null) {
            if (ctx == null) {
                throw new IllegalStateException("@Input parameter '" + input.value() + "' is only valid on an @Action ("
                        + method + ")");
            }
            return readInput(input.value(), param.getType(), ctx.view());
        }
        if (param.isAnnotationPresent(Model.class)) {
            Object model = flow.model();
            if (model != null && !param.getType().isInstance(model)) {
                throw new IllegalStateException("@Model parameter type " + param.getType().getSimpleName()
                        + " is not assignable from the flow model " + model.getClass().getSimpleName()
                        + " (" + method + ")");
            }
            return model;
        }
        Class<?> type = param.getType();
        if (type == DialogFlow.class) return flow;
        if (type == DialogContext.class) return ctx;
        if (type == Message.class) return message;
        if (Player.class.isAssignableFrom(type)
                || type == Audience.class || type == CommandSender.class) {
            return flow.player();
        }
        throw new IllegalStateException("Unsupported parameter " + param.getType().getSimpleName() + " on " + method
                + " — annotate with @Input/@Model/@ActionArg or use DialogFlow/DialogContext/Player/Message");
    }

    /** Converts a button's argument string to the {@code @ActionArg} parameter's declared type. */
    private static Object coerceActionArg(String argument, Class<?> type, Method method) {
        if (argument == null) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("Button supplied no argument but @ActionArg is primitive "
                        + type.getSimpleName() + " on " + method);
            }
            return null;
        }
        if (type == String.class) {
            return argument;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(argument.trim());
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(argument.trim());
        }
        if (type == double.class || type == Double.class) {
            return Double.valueOf(argument.trim());
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(argument.trim());
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(argument)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("Invalid @ActionArg value '" + argument + "' for "
                    + type.getSimpleName() + " on " + method);
        }
        throw new IllegalStateException("Unsupported @ActionArg type " + type.getSimpleName()
                + " on " + method);
    }

    private Object readInput(String key, Class<?> type, DialogResponseView view) {
        Class<?> boxed = box(type);
        @SuppressWarnings("unchecked")
        InputBinder<Object> binder = (InputBinder<Object>) binders.get(boxed);
        Object value;
        if (binder != null) {
            value = binder.read(view, key);
        } else if (type.isEnum()) {
            // Built-in: an option input whose ids are the enum constant names binds straight to the enum.
            value = readEnum(type, view.getText(key));
        } else {
            throw new IllegalStateException("No InputBinder registered for @Input type " + type.getSimpleName());
        }
        if (value == null && type.isPrimitive()) {
            throw new IllegalArgumentException("Input '" + key + "' was absent but parameter is primitive "
                    + type.getSimpleName());
        }
        return value;
    }

    private static Object readEnum(Class<?> type, String raw) {
        if (raw == null) return null;
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(raw)) {
                return constant;
            }
        }
        return null;   // unknown id (e.g. a malformed client response) → null, not an exception
    }

    // ── indexing & validation ─────────────────────────────────────────────────────

    private void indexHandler(DialogHandler handler) {
        Class<?> type = handler.getClass();
        try {
            Dialog ann = type.getAnnotation(Dialog.class);
            if (ann == null) {
                log.warn("DialogHandler {} is bound but has no @Dialog annotation; skipping.", type.getName());
                return;
            }
            Map<String, Method> screens = new LinkedHashMap<>();
            Map<String, Method> actions = new LinkedHashMap<>();
            List<Method> declared = new ArrayList<>(List.of(type.getDeclaredMethods()));
            declared.sort(Comparator.comparing(Method::getName));

            for (Method m : declared) {
                Screen screen = m.getAnnotation(Screen.class);
                Action action = m.getAnnotation(Action.class);
                if (screen != null) {
                    if (m.getReturnType() != DialogView.class) {
                        throw new IllegalStateException("@Screen method " + m + " must return DialogView");
                    }
                    String name = screen.value().isEmpty() ? m.getName() : screen.value();
                    if (screens.putIfAbsent(name, makeAccessible(m)) != null) {
                        throw new IllegalStateException("Duplicate @Screen name '" + name + "' on " + type.getName());
                    }
                }
                if (action != null) {
                    validateActionInputs(m);
                    if (actions.putIfAbsent(action.value(), makeAccessible(m)) != null) {
                        throw new IllegalStateException("Duplicate @Action name '" + action.value() + "' on " + type.getName());
                    }
                }
            }
            if (screens.isEmpty()) {
                throw new IllegalStateException("@Dialog class " + type.getName() + " declares no @Screen methods");
            }
            String defaultScreen = screens.containsKey(DEFAULT_SCREEN) ? DEFAULT_SCREEN : screens.keySet().iterator().next();
            String namespace = ann.value().isEmpty() ? type.getSimpleName().toLowerCase() : ann.value();
            handlers.put(type, new HandlerModel(type, handler, namespace, screens, actions, defaultScreen));
        } catch (Exception e) {
            log.error("Skipping dialog handler {}: {}", type.getName(), e.getMessage(), e);
        }
    }

    private void validateActionInputs(Method method) {
        for (Parameter param : method.getParameters()) {
            Input input = param.getAnnotation(Input.class);
            if (input == null) continue;
            Class<?> type = param.getType();
            // Enums are bound generically (constant-name match); everything else needs a binder.
            if (!type.isEnum() && !binders.containsKey(box(type))) {
                throw new IllegalStateException("@Input(\"" + input.value() + "\") on " + method
                        + " has no registered InputBinder for type " + type.getSimpleName());
            }
        }
    }

    // ── error rendering (mirrors CommandManager) ──────────────────────────────────

    private void renderError(Player viewer, Throwable t, HandlerModel hm, String where) {
        String key;
        String fallback;
        Map<String, ?> values = Map.of();
        if (t instanceof NoPermissionException) {
            key = CommandManager.KEY_NO_PERMISSION;
            fallback = DEFAULT_NO_PERMISSION;
        } else if (t instanceof BadCommandException) {
            key = CommandManager.KEY_BAD_COMMAND;
            fallback = DEFAULT_WITH_MESSAGE;
            values = Map.of("message", messageOf(t, "Invalid input."));
        } else if (t instanceof NotFoundException) {
            key = CommandManager.KEY_NOT_FOUND;
            fallback = DEFAULT_WITH_MESSAGE;
            values = Map.of("message", messageOf(t, "Not found."));
        } else if (t instanceof ConflictException) {
            key = CommandManager.KEY_CONFLICT;
            fallback = DEFAULT_WITH_MESSAGE;
            values = Map.of("message", messageOf(t, "That conflicts with something that already exists."));
        } else if (t instanceof ExceedsLimitException) {
            key = CommandManager.KEY_EXCEEDS_LIMIT;
            fallback = DEFAULT_WITH_MESSAGE;
            values = Map.of("message", messageOf(t, "That exceeds a limit."));
        } else {
            key = CommandManager.KEY_INTERNAL;
            fallback = DEFAULT_INTERNAL;
            plugin.getLogger().log(Level.SEVERE,
                    "Dialog " + hm.type.getSimpleName() + " failed in " + where, t);
        }
        viewer.sendMessage(renderError(viewer, key, fallback, values));
    }

    private Component renderError(Player viewer, String key, String fallbackPattern, Map<String, ?> values) {
        if (message != null) {
            return message.componentOr(viewer, key, fallbackPattern, values);   // viewer's locale
        }
        String pattern = fallbackPattern;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            pattern = pattern.replace("{" + e.getKey() + "}", MINI.escapeTags(Objects.toString(e.getValue())));
        }
        return MINI.deserialize(pattern);
    }

    private static String messageOf(Throwable t, String fallback) {
        if (t == null) return fallback;
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? fallback : m;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private HandlerModel requireHandler(Class<?> type) {
        HandlerModel hm = handlers.get(type);
        if (hm == null) {
            throw new IllegalArgumentException("No @Dialog handler registered for " + type.getName()
                    + " (is it bound into the DialogHandler set and did it pass validation?)");
        }
        return hm;
    }

    private static Method makeAccessible(Method m) {
        m.setAccessible(true);
        return m;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static <T> T resolve(Injector injector, Class<T> type, T fallback) {
        if (injector == null) return fallback;
        var binding = injector.getExistingBinding(Key.get(type));
        return binding != null ? binding.getProvider().get() : fallback;
    }

    /** Indexed metadata for one {@link Dialog @Dialog} handler. */
    private record HandlerModel(Class<?> type, DialogHandler instance, String namespace,
                                Map<String, Method> screens, Map<String, Method> actions, String defaultScreen) {
    }
}
