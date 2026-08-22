package io.paradaux.hibernia.framework.commander;

import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link CommandManager}'s per-handler registration phase via the historical private
 * {@code registerHandler} surface (the Paper {@code COMMANDS} lifecycle wrapper around it needs a
 * running server). Covers the skip/warn branches and the class-level permission + description wiring.
 */
class CommandManagerRegistrationTest {

    @Command(value = "alpha", description = "Alpha commands")
    @Permission("alpha.use")
    static class AlphaHandler implements CommandHandler {
        @Route("ping")
        public void ping() {
        }
    }

    @Command("beta")
    static class BetaHandler implements CommandHandler {
        @Route("pong")
        public void pong() {
        }
    }

    static class NoCommandHandler implements CommandHandler {
        @Route("x")
        public void x() {
        }
    }

    @Command("gamma")
    static class NoRouteHandler implements CommandHandler {
        public void notARoute() {
        }
    }

    @Command("delta")
    static class BadRouteHandler implements CommandHandler {
        @Route("<g> tail")
        public void bad(@GreedyArg("g") String g) {
        }
    }

    private JavaPlugin plugin;
    private Server server;
    private Logger logger;
    private CommandManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        logger = mock(Logger.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.isPrimaryThread()).thenReturn(true);
        manager = new CommandManager(plugin, Set.of(), Set.<ParameterResolver<?>>of());
    }

    @SuppressWarnings("unchecked")
    private void registerHandler(Map<String, Object> roots, List<Object> index, CommandHandler handler) throws Exception {
        Method m = CommandManager.class.getDeclaredMethod("registerHandler", Map.class, List.class, CommandHandler.class);
        m.setAccessible(true);
        try {
            m.invoke(manager, roots, index, handler);
        } catch (InvocationTargetException ite) {
            if (ite.getTargetException() instanceof Exception e) throw e;
            throw ite;
        }
    }

    private RootSpec rootSpec(Map<String, Object> roots, String label) {
        return (RootSpec) roots.get(label);
    }

    @Test
    void registerHandler_classPermissionAndDescription_areApplied() throws Exception {
        Map<String, Object> roots = new LinkedHashMap<>();
        List<Object> index = new java.util.ArrayList<>();

        registerHandler(roots, index, new AlphaHandler());

        RootSpec spec = rootSpec(roots, "alpha");
        assertTrue(spec.classPerms.contains("alpha.use"));
        assertEquals("Alpha commands", spec.description);
        assertEquals(1, index.size());
    }

    @Test
    void registerHandler_noClassPermission_marksRootOpenAccess() throws Exception {
        Map<String, Object> roots = new LinkedHashMap<>();
        List<Object> index = new java.util.ArrayList<>();

        registerHandler(roots, index, new BetaHandler());

        assertTrue(rootSpec(roots, "beta").openAccess);
    }

    @Test
    void registerHandler_skipsHandlerWithoutCommandAnnotation() throws Exception {
        Map<String, Object> roots = new LinkedHashMap<>();
        List<Object> index = new java.util.ArrayList<>();

        registerHandler(roots, index, new NoCommandHandler());

        verify(logger).warning(contains("no @Command"));
        assertTrue(roots.isEmpty());
        assertTrue(index.isEmpty());
    }

    @Test
    void registerHandler_skipsCommandWithoutRoutes() throws Exception {
        Map<String, Object> roots = new LinkedHashMap<>();
        List<Object> index = new java.util.ArrayList<>();

        registerHandler(roots, index, new NoRouteHandler());

        verify(logger).warning(contains("no @Route"));
    }

    @Test
    void registerHandler_invalidRoute_propagatesForCallerToIsolate() {
        Map<String, Object> roots = new LinkedHashMap<>();
        List<Object> index = new java.util.ArrayList<>();

        // registerAll() catches this per-handler; here we assert it fails loud at the source.
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> registerHandler(roots, index, new BadRouteHandler()))
                .getMessage().contains("greedy"));
    }

    @Test
    void errorRenderer_noPermissionException_rendersDefaultWithoutSevereLog() {
        CommandSender sender = mock(CommandSender.class);

        new ErrorRenderer(plugin, null)
                .handleInvocationFailure(sender, new NoPermissionException("denied"), "X#y");

        verify(sender).sendMessage(any(Component.class));
        verify(logger, never()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }
}
