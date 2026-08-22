package io.paradaux.hibernia.framework.commander;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.resolvers.BigDecimalResolver;
import io.paradaux.hibernia.framework.commander.resolvers.BooleanResolver;
import io.paradaux.hibernia.framework.commander.resolvers.IntegerResolver;
import io.paradaux.hibernia.framework.commander.resolvers.LongResolver;
import io.paradaux.hibernia.framework.commander.resolvers.OfflinePlayerResolver;
import io.paradaux.hibernia.framework.commander.resolvers.StringResolver;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Central manager for registering and dispatching plugin commands.
 *
 * <p>Responsibilities are split across small collaborators: {@link RouteBinder}
 * parses and validates routes, {@link CommandTreeBuilder} builds the Brigadier tree
 * and enforces conflict guarantees, and {@link ErrorRenderer} maps exceptions to user
 * feedback. This class orchestrates them — scanning handlers, wiring the lifecycle
 * event, resolving arguments at dispatch time and exposing the route index.</p>
 *
 * <p>Route syntax:
 * literals are plain tokens, required arguments are {@code <name>}, optional
 * arguments are {@code [name]}. Optional segments must form the tail of the
 * route — the command is also executable with the optional tail omitted, in
 * which case the {@link OptionalArg} defaults apply.</p>
 *
 * <p>Validation:
 * routes are validated when commands are registered, not when they are first
 * run. A route placeholder with no matching parameter, a required @Arg missing
 * from the route, a literal following an optional segment, or two routes
 * binding the same path all fail registration with a descriptive
 * {@link IllegalStateException}. A failing handler class is skipped and logged;
 * other handlers still register.</p>
 *
 * <p>Resolvers:
 * Parameter resolution and suggestions are delegated to registered {@link ParameterResolver}
 * implementations. Built-in resolvers for String, Integer, Long, BigDecimal, Boolean and
 * OfflinePlayer are registered by default; additional resolvers may be provided via
 * dependency injection into the constructor.</p>
 *
 * <p>Error handling:
 * the framework's HTTP-semantic exceptions ({@link NotFoundException},
 * {@link ConflictException}, {@link BadCommandException}, {@link ExceedsLimitException},
 * {@link NoPermissionException}) thrown from a handler are caught and rendered via
 * {@link ErrorRenderer}; each maps to a {@code hibernia.error.*} key in the consumer's
 * {@code messages.properties} when a {@code Message} bean is bound.</p>
 *
 * <p>Threading:
 * Commands annotated with {@link Async} are dispatched asynchronously; sender messages and
 * other Bukkit main-thread operations are scheduled back onto the main thread where necessary.</p>
 */
@Singleton
@Slf4j
public class CommandManager {

    /** messages.properties keys consulted (when a Message bean is bound) before the built-in defaults. */
    public static final String KEY_NO_PERMISSION    = "hibernia.error.no-permission";
    public static final String KEY_INVALID_ARGUMENT = "hibernia.error.invalid-argument";
    public static final String KEY_BAD_COMMAND      = "hibernia.error.bad-command";
    public static final String KEY_NOT_FOUND        = "hibernia.error.not-found";
    public static final String KEY_CONFLICT         = "hibernia.error.conflict";
    public static final String KEY_EXCEEDS_LIMIT    = "hibernia.error.exceeds-limit";
    public static final String KEY_INTERNAL         = "hibernia.error.internal";

    /** Sentinel cached for parameter types that have no assignable resolver (so misses aren't re-walked). */
    private static final ParameterResolver<?> NO_RESOLVER = new ParameterResolver<>() {
        public Class<Object> type() { return Object.class; }
        public Optional<Object> resolve(String token, CommandSender sender) { return Optional.empty(); }
    };

    private final JavaPlugin plugin;
    private final Set<CommandHandler> handlers;
    private final Map<Class<?>, ParameterResolver<?>> resolvers = new ConcurrentHashMap<>();
    /** Memoised resolution of a parameter type to its servicing resolver (incl. supertype/wrapper matches). */
    private final Map<Class<?>, ParameterResolver<?>> resolverCache = new ConcurrentHashMap<>();

    private final RouteBinder routeBinder = new RouteBinder();
    private final CommandTreeBuilder treeBuilder;
    private final ErrorRenderer errorRenderer;

    private volatile List<RouteInfo> routeIndex = List.of();

    /**
     * Create a CommandManager without an injector. Error messages always use the
     * built-in MiniMessage defaults rather than a consumer-bound {@code Message}.
     *
     * @param plugin the JavaPlugin instance used for scheduling and lifecycle
     * @param handlers the set of discovered CommandHandler instances to register
     * @param resolverSet additional ParameterResolver implementations to register
     */
    public CommandManager(JavaPlugin plugin, Set<CommandHandler> handlers, Set<ParameterResolver<?>> resolverSet) {
        this(plugin, handlers, resolverSet, null);
    }

    /**
     * Create a CommandManager.
     *
     * @param plugin the JavaPlugin instance used for scheduling and lifecycle
     * @param handlers the set of discovered CommandHandler instances to register
     * @param resolverSet additional ParameterResolver implementations to register
     * @param injector the Guice injector, used to discover an explicitly bound
     *                 {@code Message} bean for rendering error feedback (optional)
     */
    @Inject
    public CommandManager(JavaPlugin plugin, Set<CommandHandler> handlers,
                          Set<ParameterResolver<?>> resolverSet, Injector injector) {
        this.plugin = plugin;
        this.handlers = handlers;
        resolverSet.forEach(r -> resolvers.put(r.type(), r));
        // Built-ins
        registerResolver(new StringResolver());
        registerResolver(new IntegerResolver());
        registerResolver(new LongResolver());
        registerResolver(new BigDecimalResolver());
        registerResolver(new BooleanResolver());
        registerResolver(new OfflinePlayerResolver());

        this.treeBuilder = new CommandTreeBuilder(plugin, this::resolverFor, this::executeBinding);
        this.errorRenderer = new ErrorRenderer(plugin, injector);
    }

    /**
     * Register all commands discovered from injected CommandHandler instances.
     *
     * <p>This method hooks into the Paper lifecycle {@code COMMANDS} event and registers
     * all built Brigadier root literals returned from classes annotated with {@link Command}.
     * Handlers are processed in deterministic (class-name) order; a handler that fails
     * validation is skipped and logged without affecting the others.</p>
     */
    public void registerAll() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            Map<String, RootSpec> roots = new LinkedHashMap<>();
            List<RouteInfo> index = new ArrayList<>();

            // Guice multibinder sets have no stable iteration order; conflict
            // outcomes must not depend on it.
            List<CommandHandler> ordered = handlers.stream()
                    .sorted(Comparator.comparing(h -> h.getClass().getName()))
                    .toList();

            for (CommandHandler handler : ordered) {
                try {
                    registerHandler(roots, index, handler);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Skipping command handler " + handler.getClass().getName() + ": " + e.getMessage(), e);
                }
            }

            for (RootSpec spec : roots.values()) {
                // The root is visible to a sender holding *any* of the class-level
                // permissions registered under it. A handler class without a
                // class-level @Permission leaves the root visible to everyone;
                // per-route permissions are still enforced at execution time.
                if (!spec.openAccess && !spec.classPerms.isEmpty()) {
                    Set<String> perms = Set.copyOf(spec.classPerms);
                    spec.builder.requires(src -> perms.stream().anyMatch(p -> src.getSender().hasPermission(p)));
                }
                if (spec.description.isEmpty()) {
                    commands.register(spec.builder.build());
                } else {
                    commands.register(spec.builder.build(), spec.description);
                }
            }

            this.routeIndex = List.copyOf(index);
        });
    }

    /**
     * Metadata for every route registered in the last {@code COMMANDS} lifecycle pass.
     * Intended for consumers building help output. Empty until commands have registered.
     */
    public List<RouteInfo> routeIndex() {
        return routeIndex;
    }

    private void registerHandler(Map<String, RootSpec> roots, List<RouteInfo> index, CommandHandler handler) {
        Class<?> clazz = handler.getClass();
        Command cmdAnn = clazz.getAnnotation(Command.class);
        if (cmdAnn == null) {
            plugin.getLogger().warning("CommandHandler " + clazz.getName()
                    + " is bound but has no @Command annotation; nothing to register.");
            return;
        }

        String classPerm = Optional.ofNullable(clazz.getAnnotation(Permission.class))
                .map(Permission::value).orElse(null);

        List<Method> routeMethods = Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.getAnnotationsByType(Route.class).length > 0)
                .sorted(Comparator.comparing(Method::toGenericString))
                .toList();
        if (routeMethods.isEmpty()) {
            plugin.getLogger().warning("@Command class " + clazz.getName() + " declares no @Route methods.");
            return;
        }

        // Phase 1 — bind and validate every route. Throws before anything is
        // mutated, so a bad handler never half-registers.
        List<RouteBinding> bindings = new ArrayList<>();
        for (Method method : routeMethods) {
            for (Route route : method.getAnnotationsByType(Route.class)) {
                bindings.add(routeBinder.bind(handler, method, route, classPerm));
            }
        }

        for (String rootLabel : cmdAnn.value()) {
            RootSpec spec = roots.computeIfAbsent(rootLabel.toLowerCase(Locale.ROOT),
                    k -> new RootSpec(Commands.literal(k)));
            if (classPerm == null) {
                spec.openAccess = true;
            } else {
                spec.classPerms.add(classPerm);
            }
            if (spec.description.isEmpty() && !cmdAnn.description().isEmpty()) {
                spec.description = cmdAnn.description();
            }

            // Phase 2 — conflict-check this handler's routes against everything
            // already registered under the root (and against each other) before
            // mutating the shared tree.
            Map<String, String> staged = new LinkedHashMap<>();
            Map<String, ArgKind> stagedKinds = new LinkedHashMap<>();
            for (RouteBinding b : bindings) {
                treeBuilder.checkConflicts(spec, staged, stagedKinds, b, rootLabel);
            }
            spec.routesSeen.putAll(staged);
            spec.argKinds.putAll(stagedKinds);

            // Phase 3 — mutate the tree. Nothing below should throw.
            for (RouteBinding b : bindings) {
                treeBuilder.warnOnAmbiguousSiblings(spec, b, rootLabel);
                if (b.path.isEmpty()) {
                    spec.builder.executes(ctx -> executeBinding(ctx, b));
                } else {
                    treeBuilder.addSegments(spec.builder, b, 0, classPerm);
                }
                index.add(new RouteInfo(rootLabel, b.rawPattern, b.description, b.permission, b.async));
            }
        }
    }

    private int executeBinding(CommandContext<CommandSourceStack> context, RouteBinding binding) {
        CommandSender sender = context.getSource().getSender();

        if (binding.permission != null && !sender.hasPermission(binding.permission)) {
            errorRenderer.noPermission(sender);
            return 0;
        }

        Runnable task = () -> {
            try {
                Object[] invokeArgs = extractArguments(context, binding, sender);
                binding.method.invoke(binding.instance, invokeArgs);
            } catch (IllegalArgumentException iae) {
                errorRenderer.invalidArgument(sender, iae);
            } catch (InvocationTargetException ite) {
                errorRenderer.handleInvocationFailure(sender, ite.getTargetException(), binding.describe());
            } catch (Exception e) {
                errorRenderer.internalError(sender);
                plugin.getLogger().log(Level.SEVERE,
                        "Command dispatch failed for " + binding.describe(), e);
            }
        };

        if (binding.async) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }

        return 1;
    }

    private Object[] extractArguments(CommandContext<CommandSourceStack> context, RouteBinding binding, CommandSender sender) throws Exception {
        List<Object> values = new ArrayList<>();
        Map<String, String> flagValues = parseFlagTail(context, binding);

        for (Param param : binding.params) {
            if (param.sender) {
                values.add(injectSender(param.type, sender));
            } else if (param.flag) {
                values.add(resolveFlag(binding, param, flagValues, sender));
            } else {
                String argName = null;
                for (Segment seg : binding.path) {
                    if (!seg.literal() && seg.token().equals(param.name)) {
                        argName = seg.token();
                        break;
                    }
                }

                if (argName != null) {
                    try {
                        Object rawValue = context.getArgument(argName, Object.class);

                        // When sanitize is disabled for a String parameter, bypass the resolver
                        if (!param.sanitize && param.type == String.class) {
                            String stringValue = rawValue.toString();
                            if (stringValue.isBlank()) {
                                throw new IllegalArgumentException("Invalid " + param.name + ": blank value");
                            }
                            values.add(stringValue);
                        } else {
                            // resolverFor() handles exact, primitive↔wrapper and
                            // supertype/interface matches — so handlers can declare
                            // `boolean flag` as naturally as `Boolean flag`, and a
                            // resolver bound for a supertype services its subtypes.
                            @SuppressWarnings("unchecked")
                            ParameterResolver<Object> resolver = (ParameterResolver<Object>) resolverFor(param.type);

                            if (resolver != null) {
                                String stringValue = rawValue.toString();
                                values.add(resolver.resolve(stringValue, sender)
                                        .orElseThrow(() -> new IllegalArgumentException("Invalid " + param.name + ": " + stringValue)));
                            } else if (param.type == Integer.class || param.type == int.class
                                    || param.type == Long.class || param.type == long.class) {
                                // Brigadier's Integer/Long arg types deliver the
                                // already-typed value; pass through.
                                values.add(rawValue);
                            } else {
                                values.add(rawValue.toString());
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        if (param.optional) {
                            values.add(resolveDefault(param, sender));
                        } else {
                            throw e;
                        }
                    }
                } else if (param.optional) {
                    values.add(resolveDefault(param, sender));
                } else {
                    throw new IllegalArgumentException("Missing required argument: " + param.name);
                }
            }
        }

        return values.toArray();
    }

    /**
     * Reads the greedy flag tail off the context and parses it. The node is optional, so an
     * absent tail is the ordinary "no flags given" case and yields an empty map.
     */
    private Map<String, String> parseFlagTail(CommandContext<CommandSourceStack> context, RouteBinding binding) {
        if (binding.flags.isEmpty()) {
            return Map.of();
        }
        String raw;
        try {
            raw = context.getArgument(CommandTreeBuilder.FLAG_TAIL_ARG, String.class);
        } catch (IllegalArgumentException absent) {
            return Map.of();
        }
        return FlagTail.parse(raw, binding.flags);
    }

    /**
     * Binds one {@code @Flag} parameter from the parsed tail, falling back to its declared
     * default when the flag was not supplied. Presence flags answer the question "was it
     * given?"; value flags resolve through the same resolver registry as positional arguments,
     * so a flag and an {@code <arg>} of the same type accept exactly the same input.
     */
    private Object resolveFlag(RouteBinding binding, Param param, Map<String, String> flagValues,
                               CommandSender sender) throws Exception {
        FlagSpec found = null;
        for (FlagSpec candidate : binding.flags) {
            if (candidate.name.equals(param.name)) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            throw new IllegalStateException("No flag bound for parameter '" + param.name + "'");
        }
        final FlagSpec spec = found;

        boolean supplied = flagValues.containsKey(spec.name);
        if (spec.presence) {
            return supplied;
        }
        if (!supplied) {
            return resolveDefault(param, sender);
        }

        String raw = flagValues.get(spec.name);
        if (!spec.sanitize && spec.type == String.class) {
            return raw;
        }

        @SuppressWarnings("unchecked")
        ParameterResolver<Object> resolver = (ParameterResolver<Object>) resolverFor(spec.type);
        if (resolver != null) {
            return resolver.resolve(raw, sender)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid --" + spec.name + ": " + raw));
        }
        return raw;
    }

    private void registerResolver(ParameterResolver<?> r) {
        resolvers.putIfAbsent(r.type(), r);
    }

    /**
     * Find the resolver servicing {@code type}, or {@code null} when none is
     * registered. Resolution order: exact class, then primitive↔wrapper, then the
     * nearest registered supertype/interface (so a resolver bound for an interface
     * {@code Account} also services a {@code PersonalAccount} parameter). The
     * result — including a miss — is memoised, since the registry is fixed after
     * construction.
     */
    private ParameterResolver<?> resolverFor(Class<?> type) {
        ParameterResolver<?> cached = resolverCache.computeIfAbsent(type, this::computeResolver);
        return cached == NO_RESOLVER ? null : cached;
    }

    private ParameterResolver<?> computeResolver(Class<?> type) {
        ParameterResolver<?> direct = resolvers.get(type);
        if (direct != null) return direct;

        Class<?> wrapper = primitiveWrapper(type);
        if (wrapper != null) {
            ParameterResolver<?> wrapped = resolvers.get(wrapper);
            if (wrapped != null) return wrapped;
        }

        // Nearest assignable supertype/interface: among resolvers whose key is a
        // supertype of `type`, prefer the most specific (lowest in the hierarchy).
        ParameterResolver<?> best = null;
        Class<?> bestKey = null;
        for (Map.Entry<Class<?>, ParameterResolver<?>> entry : resolvers.entrySet()) {
            Class<?> key = entry.getKey();
            if (key.isAssignableFrom(type) && (bestKey == null || bestKey.isAssignableFrom(key))) {
                best = entry.getValue();
                bestKey = key;
            }
        }
        return best != null ? best : NO_RESOLVER;
    }

    /**
     * Convert an optional arg's default (a raw String from the annotation)
     * to the parameter's declared type, the same way a live argument is resolved.
     *
     * <p>The sentinel {@link OptionalArg#SENDER} injects the command sender when
     * the parameter type allows it (e.g. {@code OfflinePlayer} defaulting to the
     * executing player). An empty default yields {@code null} for non-String
     * reference types — primitives with an empty default are rejected at
     * registration time.</p>
     */
    @SuppressWarnings("unchecked")
    private Object resolveDefault(Param param, CommandSender sender) throws Exception {
        Class<?> type = param.type;
        String defaultValue = param.defaultValue == null ? null : param.defaultValue.toString();

        if (OptionalArg.SENDER.equals(defaultValue)) {
            if (type.isInstance(sender)) return type.cast(sender);
            throw new IllegalArgumentException(
                    "This command can only default to the sender when run by a " + type.getSimpleName()
                            + "; specify <" + param.name + "> explicitly");
        }
        if (defaultValue == null || (defaultValue.isEmpty() && type != String.class)) {
            // "No default": the handler receives null. Primitives were rejected
            // at bind time, so null is always assignable here.
            return type == String.class ? defaultValue : null;
        }
        if (type == String.class) {
            return defaultValue;
        }

        ParameterResolver<Object> resolver = (ParameterResolver<Object>) resolverFor(type);
        if (resolver != null) {
            return resolver.resolve(defaultValue, sender).orElseThrow(() ->
                    new IllegalArgumentException("Invalid default for " + param.name + ": " + defaultValue));
        }
        // No resolver registered for this type (Brigadier supplies Integer/Long
        // typed for live args); parse the default to match the declared type.
        try {
            if (type == Integer.class || type == int.class) return Integer.parseInt(defaultValue);
            if (type == Long.class || type == long.class)   return Long.parseLong(defaultValue);
            if (type == Double.class || type == double.class) return Double.parseDouble(defaultValue);
            if (type == Boolean.class || type == boolean.class) return Boolean.parseBoolean(defaultValue);
        } catch (NumberFormatException ignored) {
            // fall through to the raw string
        }
        return defaultValue;
    }

    private static Class<?> primitiveWrapper(Class<?> primitive) {
        if (!primitive.isPrimitive()) return null;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == int.class)     return Integer.class;
        if (primitive == long.class)    return Long.class;
        if (primitive == double.class)  return Double.class;
        if (primitive == float.class)   return Float.class;
        if (primitive == short.class)   return Short.class;
        if (primitive == byte.class)    return Byte.class;
        if (primitive == char.class)    return Character.class;
        return null;
    }

    private Object injectSender(Class<?> type, CommandSender sender) {
        if (type.isInstance(sender)) return type.cast(sender);
        throw new IllegalArgumentException("Sender must be " + type.getSimpleName());
    }

    // ── thin delegators retained for the reflection-based commander test suite ──────
    // The real logic lives in RouteBinder / CommandTreeBuilder / ErrorRenderer; these
    // keep the historical private surface so the per-phase tests still exercise it.

    private RouteBinding bindRoute(Object instance, Method m, Route r, String classPerm) {
        return routeBinder.bind(instance, m, r, classPerm);
    }

    private Param findParamByName(List<Param> params, String name) {
        return RouteBinder.findParamByName(params, name);
    }

    private void addSegments(ArgumentBuilder<CommandSourceStack, ?> parent,
                             RouteBinding binding, int depth, String classPerm) {
        treeBuilder.addSegments(parent, binding, depth, classPerm);
    }

    private RequiredArgumentBuilder<CommandSourceStack, ?> createArgumentBuilder(String name, Param param) {
        return treeBuilder.createArgumentBuilder(name, param);
    }

    private SuggestionProvider<CommandSourceStack> createArgumentSuggestionProvider(Param param) {
        return treeBuilder.createArgumentSuggestionProvider(param);
    }

    private SuggestionProvider<CommandSourceStack> createFlagSuggestionProvider(RouteBinding binding) {
        return treeBuilder.createFlagSuggestionProvider(binding);
    }

    private void safeMsg(CommandSender sender, Component msg) {
        errorRenderer.safeMsg(sender, msg);
    }
}
