package io.paradaux.hibernia.framework.commander;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct tests for {@link CommandTreeBuilder} — the Brigadier tree construction and ambiguity
 * warnings split out of {@code CommandManager}.
 */
class CommandTreeBuilderTest {

    private final RouteBinder routeBinder = new RouteBinder();
    private JavaPlugin plugin;
    private Logger logger;
    private CommandTreeBuilder builder;

    static class Handler {
        @Route("<x>")
        public void argX(@Arg("x") String x) {
        }

        @Route("<y>")
        public void argY(@Arg("y") String y) {
        }

        @Route("")
        public void root() {
        }

        @Route("<n>")
        public void longArg(@Arg("n") long n) {
        }

        @Route("<g>")
        public void greedyArg(@GreedyArg("g") String g) {
        }

        @Route("[a]")
        public void optionalTail(@OptionalArg(value = "a", defaultValue = "1") String a) {
        }
    }

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        Function<Class<?>, ParameterResolver<?>> lookup = t -> null;
        builder = new CommandTreeBuilder(plugin, lookup, (ctx, b) -> 1);
    }

    private RouteBinding bind(String methodName, Class<?>... params) throws Exception {
        Method method = Handler.class.getDeclaredMethod(methodName, params);
        Route route = method.getAnnotation(Route.class);
        return routeBinder.bind(new Handler(), method, route, null);
    }

    private static Param firstArgParam(RouteBinding binding) {
        for (Param p : binding.params) {
            if (!p.sender) return p;
        }
        throw new IllegalStateException("no arg param");
    }

    @Test
    void warnOnAmbiguousSiblings_logsWhenTwoDifferentArgsShareAParent() throws Exception {
        RootSpec spec = new RootSpec(Commands.literal("root"));
        builder.warnOnAmbiguousSiblings(spec, bind("argX", String.class), "root");
        builder.warnOnAmbiguousSiblings(spec, bind("argY", String.class), "root");

        verify(logger).warning(contains("Ambiguous routes"));
    }

    @Test
    void warnOnAmbiguousSiblings_quietWhenSameArgName() throws Exception {
        RootSpec spec = new RootSpec(Commands.literal("root"));
        builder.warnOnAmbiguousSiblings(spec, bind("argX", String.class), "root");
        builder.warnOnAmbiguousSiblings(spec, bind("argX", String.class), "root");
        verify(logger, org.mockito.Mockito.never()).warning(contains("Ambiguous"));
    }

    @Test
    void addSegments_emptyPath_makesParentExecutable() throws Exception {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("root");
        builder.addSegments(root, bind("root"), 0, null);
        assertNotNull(root.build().getCommand());
    }

    @Test
    void addSegments_optionalTail_makesParentExecutableAndAddsChild() throws Exception {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("root");
        builder.addSegments(root, bind("optionalTail", String.class), 0, null);
        var node = root.build();
        // executable without the optional arg…
        assertNotNull(node.getCommand());
        // …and with it.
        assertNotNull(node.getChild("a"));
    }

    @Test
    void addSegments_withClassPermission_guardsTheNode() throws Exception {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("root");
        builder.addSegments(root, bind("argX", String.class), 0, "eco.use");
        assertNotNull(root.build().getChild("x"));
    }

    @Test
    void createArgumentBuilder_longParam_usesLongType() throws Exception {
        Param param = firstArgParam(bind("longArg", long.class));
        RequiredArgumentBuilder<CommandSourceStack, ?> arg = builder.createArgumentBuilder("n", param);
        assertTrue(arg.getType() instanceof LongArgumentType);
    }

    @Test
    void createArgumentBuilder_greedyParam_usesGreedyString() throws Exception {
        Param param = firstArgParam(bind("greedyArg", String.class));
        RequiredArgumentBuilder<CommandSourceStack, ?> arg = builder.createArgumentBuilder("g", param);
        StringArgumentType type = (StringArgumentType) arg.getType();
        assertTrue(type.getType() == StringArgumentType.StringType.GREEDY_PHRASE);
    }

    @Test
    void suggestionProvider_fallsBackToPlaceholderWhenNoResolver() throws Exception {
        Param param = firstArgParam(bind("argX", String.class));
        var provider = builder.createArgumentSuggestionProvider(param);

        @SuppressWarnings("unchecked")
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx =
                mock(com.mojang.brigadier.context.CommandContext.class);
        CommandSourceStack stack = mock(CommandSourceStack.class);
        when(ctx.getSource()).thenReturn(stack);
        when(stack.getSender()).thenReturn(mock(org.bukkit.command.CommandSender.class));

        var suggestions = provider.getSuggestions(ctx, new com.mojang.brigadier.suggestion.SuggestionsBuilder("", 0)).join();
        assertTrue(suggestions.getList().stream().anyMatch(s -> s.getText().equals("<x>")));
    }
}
