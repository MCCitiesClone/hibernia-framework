package io.paradaux.hibernia.framework.commander;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Registration-time validation, runtime binding and tab-completion for {@code @Flag}.
 */
class CommandFlagTest {

    static class Target {
        final String value;

        Target(String value) {
            this.value = value;
        }
    }

    static class TargetResolver implements ParameterResolver<Target> {
        @Override
        public Class<Target> type() {
            return Target.class;
        }

        @Override
        public Optional<Target> resolve(String token, CommandSender sender) {
            return "bad".equals(token) ? Optional.empty() : Optional.of(new Target(token));
        }

        @Override
        public List<String> suggestions(String prefix, CommandSender sender) {
            return List.of("alpha", "beta");
        }
    }

    @Command("flags")
    static class FlagHandler implements CommandHandler {

        @Route("list [region]")
        public void list(@Sender CommandSender sender,
                         @OptionalArg("region") String region,
                         @Flag("page") Integer page,
                         @Flag(value = "mine", presence = true) boolean mine) {
        }

        @Route("find")
        public void find(@Flag(value = "target", aliases = "t") Target target) {
        }

        @Route("paged")
        public void paged(@Flag(value = "page", defaultValue = "1") int page) {
        }

        @Route("raw")
        public void raw(@Flag(value = "url", sanitize = false) String url) {
        }
    }

    static class BadPresenceHandler implements CommandHandler {
        @Route("x")
        public void x(@Flag(value = "n", presence = true) String notBoolean) {
        }
    }

    static class BadPrimitiveHandler implements CommandHandler {
        @Route("x")
        public void x(@Flag("n") int noDefault) {
        }
    }

    static class DuplicateFlagHandler implements CommandHandler {
        @Route("x")
        public void x(@Flag("n") String first, @Flag(value = "other", aliases = "n") String second) {
        }
    }

    static class ShadowingFlagHandler implements CommandHandler {
        @Route("x <name>")
        public void x(@Arg("name") String name, @Flag("name") String flag) {
        }
    }

    static class GreedyPlusFlagHandler implements CommandHandler {
        @Route("x <rest>")
        public void x(@GreedyArg("rest") String rest, @Flag("n") String flag) {
        }
    }

    static class BlankFlagHandler implements CommandHandler {
        @Route("x")
        public void x(@Flag("--") String blank) {
        }
    }

    static class IllegalCharFlagHandler implements CommandHandler {
        @Route("x")
        public void x(@Flag("has=equals") String bad) {
        }
    }

    private JavaPlugin plugin;
    private CommandManager manager;
    private FlagHandler handler;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Logger logger = mock(Logger.class);
        handler = new FlagHandler();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);

        Set<CommandHandler> handlers = new HashSet<>();
        handlers.add(handler);
        manager = new CommandManager(plugin, handlers, Set.of(new TargetResolver()));
    }

    // ── registration-time validation ──────────────────────────────────────────────

    @Test
    void presenceFlagOnNonBooleanIsRejected() throws Exception {
        Method method = BadPresenceHandler.class.getDeclaredMethod("x", String.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(new BadPresenceHandler(), method, "x"));

        assertTrue(thrown.getMessage().contains("boolean"));
    }

    @Test
    void primitiveValueFlagWithoutDefaultIsRejected() throws Exception {
        Method method = BadPrimitiveHandler.class.getDeclaredMethod("x", int.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(new BadPrimitiveHandler(), method, "x"));

        assertTrue(thrown.getMessage().contains("defaultValue"));
    }

    @Test
    void flagNameClaimedTwiceIsRejected() throws Exception {
        Method method = DuplicateFlagHandler.class.getDeclaredMethod("x", String.class, String.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(new DuplicateFlagHandler(), method, "x"));

        assertTrue(thrown.getMessage().contains("more than once"));
    }

    @Test
    void flagShadowingAPositionalArgumentIsRejected() throws Exception {
        Method method = ShadowingFlagHandler.class.getDeclaredMethod("x", String.class, String.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(new ShadowingFlagHandler(), method, "x <name>"));

        assertTrue(thrown.getMessage().contains("both an argument and a flag"));
    }

    @Test
    void greedyArgumentCombinedWithFlagsIsRejected() throws Exception {
        // The greedy segment would swallow the flag tail; better caught at startup.
        Method method = GreedyPlusFlagHandler.class.getDeclaredMethod("x", String.class, String.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(new GreedyPlusFlagHandler(), method, "x <rest>"));

        assertTrue(thrown.getMessage().contains("GreedyArg"));
    }

    @Test
    void blankFlagNameIsRejected() throws Exception {
        Method method = BlankFlagHandler.class.getDeclaredMethod("x", String.class);

        assertThrows(IllegalStateException.class, () -> bind(new BlankFlagHandler(), method, "x"));
    }

    @Test
    void flagNameWithIllegalCharacterIsRejected() throws Exception {
        Method method = IllegalCharFlagHandler.class.getDeclaredMethod("x", String.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(new IllegalCharFlagHandler(), method, "x"));

        assertTrue(thrown.getMessage().contains("'='"));
    }

    // ── runtime binding ───────────────────────────────────────────────────────────

    @Test
    void suppliedFlagsBindToTheirParameters() throws Exception {
        Object binding = listBinding();
        CommandContext<CommandSourceStack> context = contextWithTail("--page 3 --mine");
        when(context.getArgument("region", Object.class)).thenReturn("spawn");

        Object[] args = extract(binding, context);

        assertEquals("spawn", args[1]);
        assertEquals(3, args[2]);
        assertEquals(true, args[3]);
    }

    @Test
    void absentTailYieldsDefaultsAndFalsePresence() throws Exception {
        Object binding = listBinding();
        CommandContext<CommandSourceStack> context = contextWithoutTail();
        when(context.getArgument("region", Object.class)).thenThrow(new IllegalArgumentException("missing"));

        Object[] args = extract(binding, context);

        // An omitted String @OptionalArg yields "" rather than null; that is the
        // framework's documented contract and unaffected by flags.
        assertEquals("", args[1]);
        assertNull(args[2]);
        assertEquals(false, args[3]);
    }

    @Test
    void flagsBindWhenTheOptionalPositionalIsOmitted() throws Exception {
        // /flags list --page 2 — the truncated path must still accept the tail.
        Object binding = listBinding();
        CommandContext<CommandSourceStack> context = contextWithTail("--page 2");
        when(context.getArgument("region", Object.class)).thenThrow(new IllegalArgumentException("missing"));

        Object[] args = extract(binding, context);

        assertEquals("", args[1]);
        assertEquals(2, args[2]);
    }

    @Test
    void omittedFlagFallsBackToItsDeclaredDefault() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod("paged", int.class);
        Object binding = bind(handler, method, "paged");

        Object[] args = extract(binding, contextWithoutTail());

        assertEquals(1, args[0]);
    }

    @Test
    void flagValueResolvesThroughTheRegisteredResolver() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod("find", Target.class);
        Object binding = bind(handler, method, "find");

        Object[] args = extract(binding, contextWithTail("--target alpha"));

        assertTrue(args[0] instanceof Target);
        assertEquals("alpha", ((Target) args[0]).value);
    }

    @Test
    void aliasBindsToTheSameParameter() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod("find", Target.class);
        Object binding = bind(handler, method, "find");

        Object[] args = extract(binding, contextWithTail("--t beta"));

        assertEquals("beta", ((Target) args[0]).value);
    }

    @Test
    void unresolvableFlagValueIsReportedAgainstTheFlag() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod("find", Target.class);
        Object binding = bind(handler, method, "find");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> extract(binding, contextWithTail("--target bad")));

        assertTrue(thrown.getMessage().contains("--target"));
    }

    @Test
    void unsanitizedStringFlagKeepsItsRawValue() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod("raw", String.class);
        Object binding = bind(handler, method, "raw");

        Object[] args = extract(binding, contextWithTail("--url https://example.com/a?b=1"));

        assertEquals("https://example.com/a?b=1", args[0]);
    }

    @Test
    void unknownFlagIsRejectedAtRuntime() throws Exception {
        Object binding = listBinding();
        CommandContext<CommandSourceStack> context = contextWithTail("--bogus 1");
        when(context.getArgument("region", Object.class)).thenThrow(new IllegalArgumentException("missing"));

        assertThrows(FlagTail.FlagSyntaxException.class, () -> extract(binding, context));
    }

    // ── completion ────────────────────────────────────────────────────────────────

    @Test
    void completionOffersFlagNames() throws Exception {
        List<String> texts = suggest(listBinding(), "");

        assertTrue(texts.contains("--page"));
        assertTrue(texts.contains("--mine"));
    }

    @Test
    void completionFiltersByTypedPrefix() throws Exception {
        List<String> texts = suggest(listBinding(), "--pa");

        assertEquals(List.of("--page"), texts);
    }

    @Test
    void completionOmitsFlagsAlreadySupplied() throws Exception {
        List<String> texts = suggest(listBinding(), "--mine ");

        assertFalse(texts.contains("--mine"));
        assertTrue(texts.contains("--page"));
    }

    @Test
    void completionDelegatesValuesToTheFlagResolver() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod("find", Target.class);
        Object binding = bind(handler, method, "find");

        List<String> texts = suggest(binding, "--target ");

        assertTrue(texts.contains("alpha"));
        assertTrue(texts.contains("beta"));
    }

    @Test
    void completionFallsBackToAPlaceholderWhenNoResolverSuggestions() throws Exception {
        List<String> texts = suggest(listBinding(), "--page ");

        assertEquals(List.of("<page>"), texts);
    }

    @Test
    void completionOffersAliasesAlongsideCanonicalNames() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod("find", Target.class);
        Object binding = bind(handler, method, "find");

        List<String> texts = suggest(binding, "");

        assertTrue(texts.contains("--target"));
        assertTrue(texts.contains("--t"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private Object listBinding() throws Exception {
        Method method = FlagHandler.class.getDeclaredMethod(
                "list", CommandSender.class, String.class, Integer.class, boolean.class);
        return bind(handler, method, "list [region]");
    }

    private Object bind(Object instance, Method method, String route) throws Exception {
        Route routeAnnotation = new Route() {
            @Override
            public Class<Route> annotationType() {
                return Route.class;
            }

            @Override
            public String value() {
                return route;
            }
        };
        return invokePrivate("bindRoute",
                new Class[]{Object.class, Method.class, Route.class, String.class},
                instance, method, routeAnnotation, null);
    }

    private Object[] extract(Object binding, CommandContext<CommandSourceStack> context) throws Exception {
        Object out = invokePrivate("extractArguments",
                new Class[]{CommandContext.class, binding.getClass(), CommandSender.class},
                context, binding, mock(CommandSender.class));
        assertNotNull(out);
        return (Object[]) out;
    }

    @SuppressWarnings("unchecked")
    private List<String> suggest(Object binding, String remaining) throws Exception {
        Object provider = invokePrivate("createFlagSuggestionProvider",
                new Class[]{binding.getClass()}, binding);
        CommandContext<CommandSourceStack> context = mockContext();
        Suggestions suggestions = ((SuggestionProvider<CommandSourceStack>) provider)
                .getSuggestions(context, new SuggestionsBuilder(remaining, 0))
                .join();
        return suggestions.getList().stream().map(s -> s.getText()).toList();
    }

    private CommandContext<CommandSourceStack> mockContext() {
        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> context = mock(CommandContext.class);
        CommandSourceStack stack = mock(CommandSourceStack.class);
        when(context.getSource()).thenReturn(stack);
        when(stack.getSender()).thenReturn(mock(CommandSender.class));
        return context;
    }

    private CommandContext<CommandSourceStack> contextWithTail(String tail) {
        CommandContext<CommandSourceStack> context = mockContext();
        when(context.getArgument(CommandTreeBuilder.FLAG_TAIL_ARG, String.class)).thenReturn(tail);
        return context;
    }

    private CommandContext<CommandSourceStack> contextWithoutTail() {
        CommandContext<CommandSourceStack> context = mockContext();
        when(context.getArgument(CommandTreeBuilder.FLAG_TAIL_ARG, String.class))
                .thenThrow(new IllegalArgumentException("no such argument"));
        return context;
    }

    private Object invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = CommandManager.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(manager, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getTargetException();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw ite;
        }
    }
}
