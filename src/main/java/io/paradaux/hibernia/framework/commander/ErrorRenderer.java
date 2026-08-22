package io.paradaux.hibernia.framework.commander;

import com.google.inject.Injector;
import com.google.inject.Key;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.KeyedException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Renders command errors to the sender. Maps the framework's HTTP-semantic
 * exceptions to {@code hibernia.error.*} keys (resolved through a consumer-bound
 * {@link Message} bean when present, otherwise built-in MiniMessage defaults), and
 * resolves a {@link KeyedException}'s own message key against the plugin's bundle when
 * it defines one. Extracted from {@link CommandManager} so the error-mapping phase is
 * isolated and independently testable.
 */
final class ErrorRenderer {

    private static final String DEFAULT_NO_PERMISSION = "<red>You don't have permission to do that.</red>";
    private static final String DEFAULT_WITH_MESSAGE  = "<red>{message}</red>";
    private static final String DEFAULT_INTERNAL      = "<red>An internal error occurred. Please contact an administrator.</red>";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Injector injector;

    private volatile Message message;
    private volatile boolean messageResolved;

    ErrorRenderer(JavaPlugin plugin, Injector injector) {
        this.plugin = plugin;
        this.injector = injector;
    }

    void noPermission(CommandSender sender) {
        sendError(sender, CommandManager.KEY_NO_PERMISSION, DEFAULT_NO_PERMISSION, Map.of());
    }

    void invalidArgument(CommandSender sender, Throwable cause) {
        sendError(sender, CommandManager.KEY_INVALID_ARGUMENT, DEFAULT_WITH_MESSAGE,
                Map.of("message", messageOf(cause, "Invalid arguments.")));
    }

    void internalError(CommandSender sender) {
        sendError(sender, CommandManager.KEY_INTERNAL, DEFAULT_INTERNAL, Map.of());
    }

    /**
     * Map the framework's HTTP-semantic exceptions, thrown by the handler or
     * propagated up from the service layer, to user feedback. Anything not in
     * the taxonomy is a bug: the sender gets a generic message and the full
     * stack trace goes to the server log.
     */
    void handleInvocationFailure(CommandSender sender, Throwable t, String describe) {
        if (t instanceof NoPermissionException) {
            renderException(sender, t, CommandManager.KEY_NO_PERMISSION, DEFAULT_NO_PERMISSION, Map.of());
        } else if (t instanceof BadCommandException) {
            renderException(sender, t, CommandManager.KEY_BAD_COMMAND, DEFAULT_WITH_MESSAGE,
                    Map.of("message", messageOf(t, "Invalid command.")));
        } else if (t instanceof NotFoundException) {
            renderException(sender, t, CommandManager.KEY_NOT_FOUND, DEFAULT_WITH_MESSAGE,
                    Map.of("message", messageOf(t, "Not found.")));
        } else if (t instanceof ConflictException) {
            renderException(sender, t, CommandManager.KEY_CONFLICT, DEFAULT_WITH_MESSAGE,
                    Map.of("message", messageOf(t, "That conflicts with something that already exists.")));
        } else if (t instanceof ExceedsLimitException) {
            renderException(sender, t, CommandManager.KEY_EXCEEDS_LIMIT, DEFAULT_WITH_MESSAGE,
                    Map.of("message", messageOf(t, "That exceeds a limit.")));
        } else {
            sendError(sender, CommandManager.KEY_INTERNAL, DEFAULT_INTERNAL, Map.of());
            plugin.getLogger().log(Level.SEVERE, "Unhandled exception in " + describe, t);
        }
    }

    /**
     * Render a framework semantic exception. If its message is a key the plugin's
     * {@link Message} bundle actually defines (see {@link KeyedException}), resolve
     * <em>that</em> key in the sender's locale with the exception's placeholders.
     * Otherwise fall back to the generic {@code hibernia.error.*} rendering with the
     * raw message, preserving the behaviour of plain-string throw-sites.
     */
    private void renderException(CommandSender sender, Throwable t, String frameworkKey,
                                 String frameworkFallback, Map<String, ?> frameworkValues) {
        Message msg = resolveMessage();
        String exKey = t.getMessage();
        if (msg != null && exKey != null && !exKey.isBlank() && msg.has(sender, exKey)) {
            Map<String, ?> placeholders = (t instanceof KeyedException ke) ? ke.placeholders() : Map.of();
            safeMsg(sender, msg.componentOr(sender, exKey, exKey, placeholders));
            return;
        }
        sendError(sender, frameworkKey, frameworkFallback, frameworkValues);
    }

    private static String messageOf(Throwable t, String fallback) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? fallback : m;
    }

    /**
     * Render an error through the consumer's {@link Message} bean when one is
     * bound (so operators can re-word/translate via {@code hibernia.error.*}
     * keys), otherwise through the built-in MiniMessage default pattern.
     */
    private void sendError(CommandSender sender, String key, String fallbackPattern, Map<String, ?> values) {
        Message msg = resolveMessage();
        Component component;
        if (msg != null) {
            component = msg.componentOr(sender, key, fallbackPattern, values);   // sender's locale
        } else {
            String pattern = fallbackPattern;
            for (Map.Entry<String, ?> e : values.entrySet()) {
                pattern = pattern.replace("{" + e.getKey() + "}",
                        MINI.escapeTags(Objects.toString(e.getValue())));
            }
            component = MINI.deserialize(pattern);
        }
        safeMsg(sender, component);
    }

    /**
     * Look up an explicitly bound {@link Message} bean. Uses
     * {@code getExistingBinding} so plugins that never bound Message don't get
     * one created just-in-time (its constructor expects a bundled
     * messages.properties resource).
     */
    private Message resolveMessage() {
        if (!messageResolved) {
            messageResolved = true;
            if (injector != null) {
                var binding = injector.getExistingBinding(Key.get(Message.class));
                if (binding != null) {
                    message = binding.getProvider().get();
                }
            }
        }
        return message;
    }

    void safeMsg(CommandSender sender, Component msg) {
        if (plugin.getServer().isPrimaryThread()) {
            sender.sendMessage(msg);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(msg));
        }
    }
}
