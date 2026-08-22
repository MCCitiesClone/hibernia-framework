package io.paradaux.hibernia.framework.usher;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.paradaux.hibernia.framework.usher.annotations.Action;
import io.paradaux.hibernia.framework.usher.annotations.ActionArg;
import io.paradaux.hibernia.framework.usher.annotations.Dialog;
import io.paradaux.hibernia.framework.usher.annotations.Model;
import io.paradaux.hibernia.framework.usher.annotations.Screen;
import io.paradaux.hibernia.framework.usher.render.DialogRenderer;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Buttons that carry a payload to a shared {@code @Action}, which is what makes a
 * runtime-sized row of buttons — one per configured tag, one per search result — expressible
 * at all: the set is not known when the handler class is written, so each button cannot have
 * its own statically-named method.
 */
class ActionArgTest {

    enum Mode { IGNORE, INCLUDE, EXCLUDE }

    static final class Filter {
        final Map<String, Mode> modes = new LinkedHashMap<>();
        int lastPage;
        boolean flagged;
        long count;
        double ratio;
    }

    /** Stands in for config-defined tags: known only at render time. */
    private static final List<String> TAGS = List.of("residential", "commercial", "industrial");

    @Dialog("filter")
    static class FilterDialog implements DialogHandler {

        String missingArgSeen;

        @Screen("main")
        public DialogView main(@Model Filter filter) {
            DialogView.Builder view = DialogView.multiAction(Text.key("filter.title"));
            for (String tag : TAGS) {
                view.button(ButtonSpec.action(Text.key("tag." + tag), "cycleTag", tag));
            }
            view.button(ButtonSpec.action(Text.key("filter.page"), "goToPage", "3"));
            view.button(ButtonSpec.action(Text.key("filter.mode"), "setMode", "exclude"));
            view.button(ButtonSpec.action(Text.key("filter.flag"), "setFlag", "true"));
            view.button(ButtonSpec.action(Text.key("filter.plain"), "plain"));
            view.button(ButtonSpec.action(Text.key("filter.count"), "setCount", "42"));
            view.button(ButtonSpec.action(Text.key("filter.ratio"), "setRatio", "1.5"));
            view.button(ButtonSpec.action(Text.key("filter.bad"), "badEnum", "NOPE"));
            view.button(ButtonSpec.action(Text.key("filter.unsupported"), "unsupported", "x"));
            view.button(ButtonSpec.action(Text.key("filter.prim"), "primitiveWithoutArg"));
            return view.build();
        }

        @Action("cycleTag")
        public void cycleTag(@ActionArg String tagId, @Model Filter filter) {
            Mode current = filter.modes.getOrDefault(tagId, Mode.IGNORE);
            filter.modes.put(tagId, switch (current) {
                case IGNORE -> Mode.INCLUDE;
                case INCLUDE -> Mode.EXCLUDE;
                case EXCLUDE -> Mode.IGNORE;
            });
        }

        @Action("goToPage")
        public void goToPage(@ActionArg int page, @Model Filter filter) {
            filter.lastPage = page;
        }

        @Action("setMode")
        public void setMode(@ActionArg Mode mode, @Model Filter filter) {
            filter.modes.put("__mode", mode);
        }

        @Action("setFlag")
        public void setFlag(@ActionArg boolean flag, @Model Filter filter) {
            filter.flagged = flag;
        }

        @Action("plain")
        public void plain(@ActionArg String absent) {
            this.missingArgSeen = absent;
        }

        @Action("setCount")
        public void setCount(@ActionArg long count, @Model Filter filter) {
            filter.count = count;
        }

        @Action("setRatio")
        public void setRatio(@ActionArg double ratio, @Model Filter filter) {
            filter.ratio = ratio;
        }

        @Action("badEnum")
        public void badEnum(@ActionArg Mode mode, @Model Filter filter) {
            filter.modes.put("__bad", mode);
        }

        @Action("unsupported")
        public void unsupported(@ActionArg StringBuilder value, @Model Filter filter) {
            filter.flagged = true;
        }

        @Action("primitiveWithoutArg")
        public void primitiveWithoutArg(@ActionArg int page, @Model Filter filter) {
            filter.lastPage = page;
        }
    }

    static final class RecordingRenderer implements DialogRenderer {
        final List<DialogView> shown = new ArrayList<>();
        Function<ButtonSpec, DialogActionCallback> callbacks;
        Audience viewer;

        @Override
        public void show(Audience viewer, DialogView view,
                         Function<Text, Component> text,
                         Function<ButtonSpec, DialogActionCallback> callbacks) {
            this.viewer = viewer;
            this.shown.add(view);
            this.callbacks = callbacks;
        }

        @Override
        public void close(Audience viewer) {
        }

        DialogView last() {
            return shown.get(shown.size() - 1);
        }

        void click(ButtonSpec button, DialogResponseView view) {
            callbacks.apply(button).accept(view, viewer);
        }
    }

    private RecordingRenderer renderer;
    private DialogManager manager;
    private FilterDialog handler;
    private Filter model;
    private Player player;

    @BeforeEach
    void setUp() {
        renderer = new RecordingRenderer();
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        player = mock(Player.class);
        handler = new FilterDialog();
        model = new Filter();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        when(server.isPrimaryThread()).thenReturn(true);

        manager = new DialogManager(plugin, Set.of(handler), Set.of(), renderer);
        manager.open(player, FilterDialog.class, model);
    }

    private void click(String action, String argument) {
        ButtonSpec match = renderer.last().buttons().stream()
                .filter(b -> b.kind() == ButtonSpec.Kind.ACTION
                        && action.equals(b.target())
                        && Objects.equals(argument, b.argument()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button for " + action + "/" + argument));
        renderer.click(match, mock(DialogResponseView.class));
    }

    @Test
    void eachButtonDeliversItsOwnArgument() {
        click("cycleTag", "residential");
        click("cycleTag", "industrial");

        assertEquals(Mode.INCLUDE, model.modes.get("residential"));
        assertEquals(Mode.INCLUDE, model.modes.get("industrial"));
        assertNull(model.modes.get("commercial"));
    }

    @Test
    void repeatedClicksOnOneButtonAdvanceOnlyThatEntry() {
        click("cycleTag", "commercial");
        click("cycleTag", "commercial");

        assertEquals(Mode.EXCLUDE, model.modes.get("commercial"));
        assertTrue(model.modes.size() == 1);
    }

    @Test
    void argumentCoercesToInt() {
        click("goToPage", "3");

        assertEquals(3, model.lastPage);
    }

    @Test
    void argumentCoercesToEnumCaseInsensitively() {
        click("setMode", "exclude");

        assertEquals(Mode.EXCLUDE, model.modes.get("__mode"));
    }

    @Test
    void argumentCoercesToBoolean() {
        click("setFlag", "true");

        assertTrue(model.flagged);
    }

    @Test
    void buttonWithoutAnArgumentDeliversNull() {
        click("plain", null);

        assertNull(handler.missingArgSeen);
    }

    @Test
    void argumentCoercesToLong() {
        click("setCount", "42");

        assertEquals(42L, model.count);
    }

    @Test
    void argumentCoercesToDouble() {
        click("setRatio", "1.5");

        assertEquals(1.5d, model.ratio);
    }

    @Test
    void invalidEnumArgumentIsReportedAndTheActionDoesNotRun() {
        click("badEnum", "NOPE");

        assertNull(model.modes.get("__bad"));
    }

    @Test
    void unsupportedArgumentTypeIsReportedAndTheActionDoesNotRun() {
        click("unsupported", "x");

        assertEquals(false, model.flagged);
    }

    @Test
    void primitiveParameterWithNoArgumentIsReportedRatherThanDefaulted() {
        // A primitive cannot represent "absent"; silently passing 0 would be worse than failing.
        click("primitiveWithoutArg", null);

        assertEquals(0, model.lastPage);
    }

    @Test
    void plainActionButtonsStillCarryNoArgument() {
        ButtonSpec plain = ButtonSpec.action(Text.key("x"), "act");

        assertNull(plain.argument());
    }

    @Test
    void navigationButtonsCarryNoArgument() {
        assertNull(ButtonSpec.close(Text.key("x")).argument());
        assertNull(ButtonSpec.back(Text.key("x")).argument());
        assertNull(ButtonSpec.open(Text.key("x"), "screen").argument());
    }

    @Test
    void withTooltipAndWidthPreserveTheArgument() {
        ButtonSpec button = ButtonSpec.action(Text.key("x"), "act", "payload")
                .withTooltip(Text.key("tip"))
                .withWidth(120);

        assertEquals("payload", button.argument());
        assertEquals(120, button.width());
    }
}
