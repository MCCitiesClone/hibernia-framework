package io.paradaux.hibernia.framework.usher;

import io.papermc.paper.dialog.DialogResponseView;
import io.paradaux.hibernia.framework.usher.input.DialogInputSpec;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the small renderer-agnostic value types of the dialog tier: {@link Text},
 * {@link ButtonSpec}, {@link DialogContext} and {@link DialogInputSpec} factories that
 * the orchestration tests don't exercise directly.
 */
class UsherValueTypesTest {

    // ── Text ─────────────────────────────────────────────────────────────────────

    @Test
    void text_literal_wrapsComponent() {
        Component c = Component.text("hello");
        Text.Literal literal = (Text.Literal) Text.of(c);
        assertSame(c, literal.component());
    }

    @Test
    void text_of_rejectsNull() {
        assertThrows(NullPointerException.class, () -> Text.of(null));
    }

    @Test
    void text_key_rejectsNullKey() {
        assertThrows(NullPointerException.class, () -> Text.key(null));
    }

    @Test
    void text_key_buildsOrderedPlaceholderMap() {
        Text.Keyed keyed = (Text.Keyed) Text.key("k", "a", 1, "b", 2);
        assertEquals(2, keyed.placeholders().size());
        assertEquals(1, keyed.placeholders().get("a"));
        assertEquals(2, keyed.placeholders().get("b"));
    }

    @Test
    void text_key_rejectsNonStringPlaceholderName() {
        assertThrows(IllegalArgumentException.class, () -> Text.key("k", 42, "value"));
    }

    @Test
    void text_key_rejectsOddPlaceholderCount() {
        assertThrows(IllegalArgumentException.class, () -> Text.key("k", "only-one"));
    }

    // ── ButtonSpec ────────────────────────────────────────────────────────────────

    @Test
    void buttonSpec_factories_setKindAndTarget() {
        Text label = Text.key("l");
        assertEquals(ButtonSpec.Kind.ACTION, ButtonSpec.action(label, "go").kind());
        assertEquals(ButtonSpec.Kind.CLOSE, ButtonSpec.close(label).kind());
        assertEquals(ButtonSpec.Kind.BACK, ButtonSpec.back(label).kind());
        ButtonSpec open = ButtonSpec.open(label, "filters");
        assertEquals(ButtonSpec.Kind.OPEN, open.kind());
        assertEquals("filters", open.target());
    }

    @Test
    void buttonSpec_withTooltipAndWidth_returnCopies() {
        Text label = Text.key("l");
        Text tip = Text.key("t");
        ButtonSpec base = ButtonSpec.action(label, "go");

        ButtonSpec tipped = base.withTooltip(tip);
        assertSame(tip, tipped.tooltip());
        assertEquals("go", tipped.target());

        ButtonSpec widened = tipped.withWidth(120);
        assertEquals(120, widened.width());
        assertSame(tip, widened.tooltip());
    }

    @Test
    void buttonSpec_actionRequiresTarget() {
        assertThrows(IllegalArgumentException.class, () -> ButtonSpec.action(Text.key("l"), "  "));
        assertThrows(IllegalArgumentException.class, () -> ButtonSpec.open(Text.key("l"), null));
    }

    @Test
    void buttonSpec_requiresLabelAndKind() {
        assertThrows(NullPointerException.class, () -> new ButtonSpec(null, null, 0, ButtonSpec.Kind.CLOSE, null, null));
    }

    // ── DialogContext ─────────────────────────────────────────────────────────────

    @Test
    void dialogContext_delegatesToViewAndFlow() {
        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText("name")).thenReturn("Sam");
        when(view.getBoolean("flag")).thenReturn(Boolean.TRUE);
        when(view.getFloat("amount")).thenReturn(2.5f);

        DialogContext ctx = new DialogContext(view, null);
        assertEquals("Sam", ctx.text("name"));
        assertEquals(Boolean.TRUE, ctx.bool("flag"));
        assertEquals(2.5f, ctx.number("amount"));
        assertSame(view, ctx.view());
    }

    // ── DialogInputSpec ─────────────────────────────────────────────────────────────

    @Test
    void inputSpec_text_factory() {
        DialogInputSpec spec = DialogInputSpec.text("name", Text.key("l"));
        assertEquals(DialogInputSpec.Kind.TEXT, spec.kind());
        assertEquals("name", spec.key());
        assertTrue(spec.options().isEmpty());
    }

    @Test
    void inputSpec_bool_factory_storesInitialAsFloat() {
        assertEquals(1f, DialogInputSpec.bool("k", Text.key("l"), true).initial());
        assertEquals(0f, DialogInputSpec.bool("k", Text.key("l"), false).initial());
    }

    @Test
    void inputSpec_option_rejectsEmptyOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> DialogInputSpec.option("k", Text.key("l"), List.of()));
    }

    @Test
    void inputSpec_withWidth_returnsCopy() {
        DialogInputSpec spec = DialogInputSpec.text("k", Text.key("l")).withWidth(80);
        assertEquals(80, spec.width());
        assertEquals(DialogInputSpec.Kind.TEXT, spec.kind());
    }

    @Test
    void optionSpec_requiresIdAndLabel() {
        assertThrows(NullPointerException.class,
                () -> new DialogInputSpec.OptionSpec(null, Text.key("l"), false));
        assertThrows(NullPointerException.class,
                () -> new DialogInputSpec.OptionSpec("id", null, false));
    }

    @Test
    void inputSpec_canonicalConstructor_defaultsNullOptionsToEmpty() {
        DialogInputSpec spec = new DialogInputSpec(
                DialogInputSpec.Kind.TEXT, "k", Text.key("l"), null, 0, 0, null, null, 0);
        assertNull(spec.step());
        assertTrue(spec.options().isEmpty());
    }
}
