package io.paradaux.hibernia.framework.usher;

import com.google.inject.Binding;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.annotations.Action;
import io.paradaux.hibernia.framework.usher.annotations.Dialog;
import io.paradaux.hibernia.framework.usher.annotations.Input;
import io.paradaux.hibernia.framework.usher.annotations.Model;
import io.paradaux.hibernia.framework.usher.annotations.Screen;
import io.paradaux.hibernia.framework.usher.render.DialogRenderer;
import io.paradaux.hibernia.framework.usher.spi.BedrockSupport;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Edge-path coverage for {@link DialogManager} and {@link DialogFlow}: text resolution,
 * screen/action error mapping, parameter injection rejections, primitive boxing, handler
 * indexing failures, and the Guice-injected ({@code Message}/{@code BedrockSupport}) wiring —
 * complementing the happy-path {@link DialogManagerTest}.
 */
class DialogManagerCoverageTest {

    enum Choice { BUY, SELL }

    static final class Model1 {
        String name = "m1";
    }

    @Dialog("cov")
    static class CoverDialog implements DialogHandler {
        Object lastEnum = "unset";
        long lastLong;
        double lastDouble;
        float lastFloat;
        boolean injected;

        @Screen
        public DialogView main() {
            return DialogView.multiAction("main.title").button("b", "noop").build();
        }

        @Screen("second")
        public DialogView second() {
            return DialogView.notice("second.title").build();
        }

        @Screen("nullscreen")
        public DialogView nullScreen() {
            return null;
        }

        @Screen("boomscreen")
        public DialogView boomScreen() {
            throw new NotFoundException("screen boom");
        }

        @Screen("inputscreen")
        public DialogView inputScreen(@Input("x") String x) {
            return DialogView.notice("t").build();
        }

        @Screen("modelscreen")
        public DialogView modelScreen(@Model String model) {
            return DialogView.notice("t").build();
        }

        @Screen("badparam")
        public DialogView badParam(Integer notAnnotated) {
            return DialogView.notice("t").build();
        }

        @Screen("inject")
        public DialogView inject(DialogFlow flow, Player viewer, Audience aud, CommandSender cs, Message msg) {
            injected = true;
            return DialogView.notice("t").build();
        }

        @Action("noop")
        public void noop() {
        }

        @Action("enumAction")
        public void enumAction(@Input("type") Choice type) {
            this.lastEnum = type;
        }

        @Action("longAction")
        public void longAction(@Input("n") long n) {
            this.lastLong = n;
        }

        @Action("doubleAction")
        public void doubleAction(@Input("n") double n) {
            this.lastDouble = n;
        }

        @Action("floatAction")
        public void floatAction(@Input("n") float n) {
            this.lastFloat = n;
        }

        @Action("intAction")
        public void intAction(@Input("n") int n) {
        }

        @Action("ctxAction")
        public void ctxAction(DialogContext ctx, Player viewer) {
        }

        @Action("noPerm")
        public void noPerm() {
            throw new NoPermissionException("nope");
        }

        @Action("badCmd")
        public void badCmd() {
            throw new BadCommandException("bad input msg");
        }

        @Action("conflict")
        public void conflict() {
            throw new ConflictException("conflict msg");
        }

        @Action("limit")
        public void limit() {
            throw new ExceedsLimitException("limit msg");
        }
    }

    // ── handlers that fail indexing (skipped + logged) ───────────────────────────

    @Dialog("dupscreen")
    static class DuplicateScreenDialog implements DialogHandler {
        @Screen("x")
        public DialogView a() {
            return DialogView.notice("t").build();
        }

        @Screen("x")
        public DialogView b() {
            return DialogView.notice("t").build();
        }
    }

    @Dialog("dupaction")
    static class DuplicateActionDialog implements DialogHandler {
        @Screen
        public DialogView main() {
            return DialogView.notice("t").build();
        }

        @Action("go")
        public void a() {
        }

        @Action("go")
        public void b() {
        }
    }

    @Dialog("badreturn")
    static class BadReturnDialog implements DialogHandler {
        @Screen
        public String main() {
            return "not a view";
        }
    }

    private RecordingRenderer renderer;
    private JavaPlugin plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private Logger logger;
    private Player player;
    private CoverDialog handler;

    @BeforeEach
    void setUp() {
        renderer = new RecordingRenderer();
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        logger = mock(Logger.class);
        player = mock(Player.class);
        handler = new CoverDialog();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);
    }

    private DialogManager manager() {
        return new DialogManager(plugin, Set.of(handler), Set.of(), renderer);
    }

    // ── text resolution ──────────────────────────────────────────────────────────

    @Test
    void resolveText_literalReturnsComponentVerbatim() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);

        Component c = Component.text("verbatim");
        assertSame(c, renderer.text.apply(Text.of(c)));
    }

    @Test
    void resolveText_keyedWithoutMessageBeanDeserialisesAsMiniMessage() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);

        Component out = renderer.text.apply(Text.key("<red>hi</red>"));
        assertEquals("hi", PlainTextComponentSerializer.plainText().serialize(out));
    }

    @Test
    void resolveText_keyedWithMessageBean_usesPlayerLocaleWhenPresent() {
        Message message = mock(Message.class);
        when(message.component(any(Locale.class), anyString(), anyMap())).thenReturn(Component.text("localised"));
        when(player.locale()).thenReturn(Locale.of("ga"));

        DialogManager m = new DialogManager(plugin, Set.of(handler), Set.of(), renderer,
                injectorWith(BedrockSupport.NONE, message));
        m.open(player, CoverDialog.class, null);

        Component out = renderer.text.apply(Text.key("some.key", "a", 1));
        assertEquals("localised", PlainTextComponentSerializer.plainText().serialize(out));
        verify(message).component(eq(Locale.of("ga")), eq("some.key"), anyMap());
    }

    @Test
    void resolveText_keyedWithMessageBean_usesDefaultWhenLocaleNull() {
        Message message = mock(Message.class);
        when(message.component(anyString(), anyMap())).thenReturn(Component.text("default-locale"));
        when(player.locale()).thenReturn(null);

        DialogManager m = new DialogManager(plugin, Set.of(handler), Set.of(), renderer,
                injectorWith(BedrockSupport.NONE, message));
        m.open(player, CoverDialog.class, null);

        Component out = renderer.text.apply(Text.key("some.key"));
        assertEquals("default-locale", PlainTextComponentSerializer.plainText().serialize(out));
        verify(message).component(eq("some.key"), anyMap());
    }

    // ── screen errors ─────────────────────────────────────────────────────────────

    @Test
    void renderScreen_nullView_warnsAndShowsNothing() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, "nullscreen", null);

        // A null view is logged (via slf4j) and nothing is shown or sent.
        assertTrue(renderer.shown.isEmpty());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void renderScreen_screenThrows_rendersSemanticErrorToViewer() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, "boomscreen", null);

        verify(player).sendMessage(any(Component.class));
        verify(logger, never()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }

    @Test
    void renderScreen_inputParamOnScreen_isRejected() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, "inputscreen", null);
        // @Input is only valid on @Action: the screen render fails and an error is shown.
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void renderScreen_modelTypeMismatch_isRejected() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, "modelscreen", new Model1());
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void renderScreen_unsupportedParam_isRejected() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, "badparam", null);
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void renderScreen_injectsFrameworkParams() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, "inject", null);
        assertTrue(handler.injected);
        assertEquals(DialogView.Kind.NOTICE, renderer.last().kind());
    }

    // ── action dispatch & navigation via raw callbacks ───────────────────────────

    @Test
    void callback_backNavigatesToPreviousScreen() {
        DialogManager m = manager();
        DialogFlow flow = m.open(player, CoverDialog.class, null);
        flow.open("second");                 // stack: [second, main]
        assertEquals(DialogView.Kind.NOTICE, renderer.last().kind());

        renderer.callbacks.apply(ButtonSpec.back(Text.key("x"))).accept(emptyView(), player);
        // back popped "second" → re-rendered "main"
        assertEquals(DialogView.Kind.MULTI_ACTION, renderer.last().kind());
    }

    @Test
    void callback_openToUnknownScreen_isCaughtAndRendersError() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);

        renderer.callbacks.apply(ButtonSpec.open(Text.key("x"), "does-not-exist")).accept(emptyView(), player);
        verify(player, atLeastOnce()).sendMessage(any(Component.class));
    }

    @Test
    void dispatch_missingAction_warnsAndRendersError() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);

        clickAction(m, "no-such-action", emptyView());
        // Missing action → warn (slf4j) then a generic (null-throwable) error rendered to the viewer.
        verify(player, atLeastOnce()).sendMessage(any(Component.class));
        verify(logger).log(eq(Level.SEVERE), anyString(), nullable(Throwable.class));
    }

    @Test
    void dispatch_enumInput_unknownIdBindsNull() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);

        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText("type")).thenReturn("INVALID");
        clickAction(m, "enumAction", view);

        assertNull(handler.lastEnum);
    }

    @Test
    void dispatch_longDoubleFloatInputs_boxAndBind() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);

        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getFloat("n")).thenReturn(5f);

        clickAction(m, "longAction", view);
        clickAction(m, "doubleAction", view);
        clickAction(m, "floatAction", view);

        assertEquals(5L, handler.lastLong);
        assertEquals(5d, handler.lastDouble);
        assertEquals(5f, handler.lastFloat);
    }

    @Test
    void dispatch_primitiveInputBindsNull_rendersError() {
        // A binder that yields null for a primitive @Input must be rejected, not coerced.
        io.paradaux.hibernia.framework.usher.spi.InputBinder<Integer> nullBinder =
                new io.paradaux.hibernia.framework.usher.spi.InputBinder<>() {
                    @Override
                    public Class<Integer> type() {
                        return Integer.class;
                    }

                    @Override
                    public Integer read(DialogResponseView view, String key) {
                        return null;
                    }
                };
        DialogManager m = new DialogManager(plugin, Set.of(handler), Set.of(nullBinder), renderer);
        m.open(player, CoverDialog.class, null);

        clickAction(m, "intAction", emptyView());
        verify(player, atLeastOnce()).sendMessage(any(Component.class));
    }

    @Test
    void dispatch_contextAndPlayerParams_inject() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);
        clickAction(m, "ctxAction", emptyView());
        // No exception path; the action ran with injected DialogContext + Player.
        verify(player, never()).sendMessage(any(Component.class));
    }

    // ── semantic exception mapping ────────────────────────────────────────────────

    @Test
    void dispatch_eachSemanticException_rendersWithoutSevereLog() {
        DialogManager m = manager();
        m.open(player, CoverDialog.class, null);

        for (String action : List.of("noPerm", "badCmd", "conflict", "limit")) {
            clickAction(m, action, emptyView());
        }
        verify(player, atLeastOnce()).sendMessage(any(Component.class));
        verify(logger, never()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }

    @Test
    void renderError_withMessageBean_usesComponentOr() {
        Message message = mock(Message.class);
        when(message.componentOr(any(Player.class), anyString(), anyString(), anyMap()))
                .thenReturn(Component.text("rendered"));

        DialogManager m = new DialogManager(plugin, Set.of(handler), Set.of(), renderer,
                injectorWith(BedrockSupport.NONE, message));
        m.open(player, CoverDialog.class, null);
        clickAction(m, "badCmd", emptyView());

        verify(message).componentOr(any(Player.class), anyString(), anyString(), anyMap());
        verify(player).sendMessage(any(Component.class));
    }

    // ── indexing failures ─────────────────────────────────────────────────────────

    @Test
    void indexing_duplicateScreenName_skipsHandler() {
        DialogManager m = new DialogManager(plugin, Set.of(new DuplicateScreenDialog()), Set.of(), renderer);
        assertThrows(IllegalArgumentException.class,
                () -> m.open(player, DuplicateScreenDialog.class, null));
    }

    @Test
    void indexing_duplicateActionName_skipsHandler() {
        DialogManager m = new DialogManager(plugin, Set.of(new DuplicateActionDialog()), Set.of(), renderer);
        assertThrows(IllegalArgumentException.class,
                () -> m.open(player, DuplicateActionDialog.class, null));
    }

    @Test
    void indexing_screenWithWrongReturnType_skipsHandler() {
        DialogManager m = new DialogManager(plugin, Set.of(new BadReturnDialog()), Set.of(), renderer);
        assertThrows(IllegalArgumentException.class,
                () -> m.open(player, BadReturnDialog.class, null));
    }

    // ── Guice-injected wiring ─────────────────────────────────────────────────────

    @Test
    void bedrockSupport_fromInjector_flagsBedrockViewers() {
        BedrockSupport bedrock = viewer -> true;
        DialogManager m = new DialogManager(plugin, Set.of(handler), Set.of(), renderer,
                injectorWith(bedrock, null));

        DialogFlow flow = m.open(player, CoverDialog.class, null);
        assertTrue(flow.isBedrockViewer());
    }

    // ── DialogFlow direct ─────────────────────────────────────────────────────────

    @Test
    void flow_backWithSingleScreen_closes() {
        DialogManager m = manager();
        DialogFlow flow = m.open(player, CoverDialog.class, null);   // stack: [main]
        flow.back();
        assertTrue(renderer.closes >= 1);
        assertNull(flow.current());
    }

    @Test
    void flow_refresh_reRendersCurrentScreen() {
        DialogManager m = manager();
        DialogFlow flow = m.open(player, CoverDialog.class, null);
        int before = renderer.shown.size();
        flow.refresh();
        assertEquals(before + 1, renderer.shown.size());
    }

    @Test
    void flow_refreshBeforeOpen_isNoOp() {
        DialogManager m = manager();
        DialogFlow flow = new DialogFlow(m, player, CoverDialog.class, null, false);
        flow.refresh();   // current() == null → nothing rendered
        assertTrue(renderer.shown.isEmpty());
        assertNull(flow.current());
    }

    @Test
    void flow_accessors_exposeViewerAndModel() {
        DialogManager m = manager();
        Model1 model = new Model1();
        DialogFlow flow = new DialogFlow(m, player, CoverDialog.class, model, true);

        assertSame(player, flow.player());
        assertSame(player, flow.viewer());
        assertSame(model, flow.model());
        assertTrue(flow.isBedrockViewer());
        assertNull(flow.current());
    }

    @Test
    void flow_awaitSuccess_runsCompletionOnMainThread() {
        DialogManager m = manager();
        DialogFlow flow = m.open(player, CoverDialog.class, null);

        List<String> got = new ArrayList<>();
        flow.await(CompletableFuture.completedFuture("RESULT"), Text.key("wait"),
                (result, f) -> got.add(result));

        assertEquals(List.of("RESULT"), got);
    }

    @Test
    void flow_awaitFailure_rendersAsyncError() {
        DialogManager m = manager();
        DialogFlow flow = m.open(player, CoverDialog.class, null);

        flow.await(CompletableFuture.failedFuture(new RuntimeException("async boom")),
                Text.key("wait"), (result, f) -> {
                });

        // The failure routes through handleAsyncError → renderError → generic message + severe log.
        verify(player, atLeastOnce()).sendMessage(any(Component.class));
        verify(logger).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }

    @Test
    void runMain_offThread_schedulesOntoMainThread() {
        when(server.isPrimaryThread()).thenReturn(false);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(scheduler).runTask(any(JavaPlugin.class), any(Runnable.class));

        DialogManager m = manager();
        DialogFlow flow = m.open(player, CoverDialog.class, null);

        List<String> got = new ArrayList<>();
        flow.await(CompletableFuture.completedFuture("R"), Text.key("wait"), (r, f) -> got.add(r));

        assertEquals(List.of("R"), got);
        verify(scheduler).runTask(any(JavaPlugin.class), any(Runnable.class));
    }

    @Test
    void dialogContext_audienceDelegatesToFlowViewer() {
        DialogManager m = manager();
        DialogFlow flow = new DialogFlow(m, player, CoverDialog.class, null, false);
        DialogContext ctx = new DialogContext(mock(DialogResponseView.class), flow);
        assertSame(player, ctx.audience());
    }

    @Test
    void box_mapsRemainingPrimitivesAndPassesThroughReferences() throws Exception {
        Method box = DialogManager.class.getDeclaredMethod("box", Class.class);
        box.setAccessible(true);
        assertEquals(Short.class, box.invoke(null, short.class));
        assertEquals(Byte.class, box.invoke(null, byte.class));
        assertEquals(Character.class, box.invoke(null, char.class));
        assertEquals(Double.class, box.invoke(null, double.class));
        assertEquals(Float.class, box.invoke(null, float.class));
        assertEquals(String.class, box.invoke(null, String.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private DialogResponseView emptyView() {
        return mock(DialogResponseView.class);
    }

    private void clickAction(DialogManager m, String action, DialogResponseView view) {
        renderer.callbacks.apply(ButtonSpec.action(Text.key("x"), action)).accept(view, player);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Injector injectorWith(BedrockSupport bedrock, Message message) {
        Injector injector = mock(Injector.class);
        bindReturn(injector, BedrockSupport.class, bedrock);
        bindReturn(injector, Message.class, message);
        return injector;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void bindReturn(Injector injector, Class<T> type, T value) {
        if (value == null) {
            when(injector.getExistingBinding(Key.get(type))).thenReturn(null);
            return;
        }
        Binding binding = mock(Binding.class);
        Provider provider = mock(Provider.class);
        when(injector.getExistingBinding(Key.get(type))).thenReturn(binding);
        when(binding.getProvider()).thenReturn(provider);
        when(provider.get()).thenReturn(value);
    }

    static final class RecordingRenderer implements DialogRenderer {
        final List<DialogView> shown = new ArrayList<>();
        Function<ButtonSpec, DialogActionCallback> callbacks;
        Function<Text, Component> text;
        int closes;

        @Override
        public void show(Audience viewer, DialogView view,
                         Function<Text, Component> text,
                         Function<ButtonSpec, DialogActionCallback> callbacks) {
            this.shown.add(view);
            this.text = text;
            this.callbacks = callbacks;
        }

        @Override
        public void close(Audience viewer) {
            this.closes++;
        }

        DialogView last() {
            return shown.get(shown.size() - 1);
        }
    }
}
