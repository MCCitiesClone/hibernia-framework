package io.paradaux.hibernia.framework.usher;

import io.paradaux.hibernia.framework.usher.input.DialogInputSpec;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Covers {@link DialogView.Builder} options and getters that {@link DialogViewTest} doesn't exercise:
 * external title, item bodies, the {@code text}/{@code bool} inputs, the {@code button(ButtonSpec)} and
 * keyed {@code deny} overloads, and the spec accessors.
 */
class DialogViewBuilderCoverageTest {

    @Test
    void multiAction_externalTitleItemBodyAndInputsAndButtonSpec() {
        ItemStack item = mock(ItemStack.class);
        Text external = Text.key("ext.title");
        ButtonSpec custom = ButtonSpec.action(Text.key("custom"), "doThing").withWidth(100);

        DialogView view = DialogView.multiAction(Text.key("main.title"))
                .externalTitle(external)
                .afterAction(DialogView.AfterAction.CLOSE)
                .bodyItem(item)
                .text("name", Text.key("name.label"))
                .bool("flag", Text.key("flag.label"), true)
                .button(custom)
                .build();

        // accessors
        assertEquals(DialogView.Kind.MULTI_ACTION, view.kind());
        assertInstanceOf(Text.Keyed.class, view.title());
        assertSame(external, view.externalTitle());
        assertEquals(DialogView.AfterAction.CLOSE, view.afterAction());

        // item body
        assertEquals(1, view.bodies().size());
        DialogView.Body.Item body = assertInstanceOf(DialogView.Body.Item.class, view.bodies().get(0));
        assertSame(item, body.item());

        // inputs
        assertEquals(2, view.inputs().size());
        assertEquals(DialogInputSpec.Kind.TEXT, view.inputs().get(0).kind());
        assertEquals(DialogInputSpec.Kind.BOOLEAN, view.inputs().get(1).kind());

        // button(ButtonSpec) added verbatim
        assertEquals("doThing", view.buttons().get(0).target());
        assertEquals(100, view.buttons().get(0).width());
    }

    @Test
    void confirmation_denyWithActionOverload() {
        DialogView view = DialogView.confirmation("delete.title")
                .confirm("yes", "doDelete")
                .deny("no", "cancelAction")   // (labelKey, action) overload → an ACTION deny button
                .build();

        assertEquals(2, view.buttons().size());
        assertEquals(ButtonSpec.Kind.ACTION, view.buttons().get(1).kind());
        assertEquals("cancelAction", view.buttons().get(1).target());
    }
}
