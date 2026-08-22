package io.paradaux.hibernia.framework.commander;

import com.google.inject.Binding;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorRendererTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private JavaPlugin plugin;
    private Server server;
    private Logger logger;
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        logger = mock(Logger.class);
        sender = mock(CommandSender.class);
        when(plugin.getServer()).thenReturn(server);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        when(server.isPrimaryThread()).thenReturn(true);
    }

    private String sentText() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(captor.capture());
        return PLAIN.serialize(captor.getValue());
    }

    // ── without a Message bean: built-in MiniMessage defaults ────────────────────────

    @Test
    void noPermission_withoutMessageBean_usesDefault() {
        new ErrorRenderer(plugin, null).noPermission(sender);
        assertTrue(sentText().toLowerCase().contains("permission"));
    }

    @Test
    void invalidArgument_includesCauseMessage() {
        new ErrorRenderer(plugin, null).invalidArgument(sender, new IllegalArgumentException("bad number"));
        assertTrue(sentText().contains("bad number"));
    }

    @Test
    void invalidArgument_blankCauseUsesFallback() {
        new ErrorRenderer(plugin, null).invalidArgument(sender, new IllegalArgumentException("   "));
        assertTrue(sentText().contains("Invalid arguments."));
    }

    @Test
    void internalError_usesGenericMessage() {
        new ErrorRenderer(plugin, null).internalError(sender);
        assertTrue(sentText().toLowerCase().contains("internal error"));
    }

    @Test
    void handleInvocationFailure_notFound_rendersItsMessage() {
        new ErrorRenderer(plugin, null).handleInvocationFailure(sender, new NotFoundException("widget missing"), "X#y");
        assertTrue(sentText().contains("widget missing"));
        verify(logger, never()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }

    @Test
    void handleInvocationFailure_badCommandAndConflictAndLimit_renderMessages() {
        new ErrorRenderer(plugin, null).handleInvocationFailure(sender, new BadCommandException("nope"), "X#y");
        assertTrue(sentText().contains("nope"));

        CommandSender s2 = mock(CommandSender.class);
        new ErrorRenderer(plugin, null).handleInvocationFailure(s2, new ConflictException("dupe"), "X#y");
        verify(s2).sendMessage(any(Component.class));

        CommandSender s3 = mock(CommandSender.class);
        new ErrorRenderer(plugin, null).handleInvocationFailure(s3, new ExceedsLimitException("too much"), "X#y");
        verify(s3).sendMessage(any(Component.class));
    }

    @Test
    void handleInvocationFailure_unknownException_isGenericAndLogged() {
        new ErrorRenderer(plugin, null).handleInvocationFailure(sender, new RuntimeException("kaboom"), "X#y");
        String text = sentText();
        assertFalse(text.contains("kaboom"), "raw message must not leak");
        assertTrue(text.toLowerCase().contains("internal error"));
        verify(logger).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }

    @Test
    void noPermission_keyedException_throughBean_isUnusedForPrecheck() {
        // noPermission() is the framework pre-check, not an exception path: always the generic key.
        Injector injector = injectorWith(messageWithKey("ignored", false));
        new ErrorRenderer(plugin, injector).noPermission(sender);
        verify(sender).sendMessage(any(Component.class));
    }

    // ── with a Message bean: keyed-exception resolution (PAR-16) ─────────────────────

    @Test
    void handleInvocationFailure_keyedException_resolvesPluginKey() {
        Component resolved = Component.text("Localised: no widget");
        Message msg = mock(Message.class);
        when(msg.has(eq(sender), eq("myplugin.widget.missing"))).thenReturn(true);
        when(msg.componentOr(eq(sender), eq("myplugin.widget.missing"), anyString(), any()))
                .thenReturn(resolved);

        ErrorRenderer renderer = new ErrorRenderer(plugin, injectorWith(msg));
        renderer.handleInvocationFailure(sender,
                new NotFoundException("myplugin.widget.missing", "name", "Widget"), "X#y");

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(captor.capture());
        assertSame(resolved, captor.getValue());
        verify(msg).componentOr(eq(sender), eq("myplugin.widget.missing"), anyString(),
                eq(Map.of("name", "Widget")));
    }

    @Test
    void handleInvocationFailure_keyAbsent_fallsBackToGeneric() {
        Message msg = mock(Message.class);
        when(msg.has(any(CommandSender.class), anyString())).thenReturn(false);
        // generic path goes through componentOr with the framework key + fallback pattern
        when(msg.componentOr(any(CommandSender.class), anyString(), anyString(), any()))
                .thenReturn(Component.text("generic not found"));

        new ErrorRenderer(plugin, injectorWith(msg))
                .handleInvocationFailure(sender, new NotFoundException("not a defined key"), "X#y");

        verify(msg).componentOr(eq(sender), eq(CommandManager.KEY_NOT_FOUND), anyString(), any());
    }

    @Test
    void safeMsg_offThread_schedulesOnMainThread() {
        when(server.isPrimaryThread()).thenReturn(false);
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(any(JavaPlugin.class), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        });
        Component msg = Component.text("hi");
        new ErrorRenderer(plugin, null).safeMsg(sender, msg);
        verify(sender).sendMessage(msg);
    }

    private Message messageWithKey(String key, boolean present) {
        Message msg = mock(Message.class);
        lenient().when(msg.has(any(CommandSender.class), anyString())).thenReturn(present);
        lenient().when(msg.componentOr(any(CommandSender.class), anyString(), anyString(), any()))
                .thenReturn(Component.text("x"));
        return msg;
    }

    @SuppressWarnings("unchecked")
    private Injector injectorWith(Message msg) {
        Injector injector = mock(Injector.class);
        Binding<Message> binding = mock(Binding.class);
        Provider<Message> provider = mock(Provider.class);
        when(injector.getExistingBinding(Key.get(Message.class))).thenReturn(binding);
        when(binding.getProvider()).thenReturn(provider);
        when(provider.get()).thenReturn(msg);
        return injector;
    }
}
