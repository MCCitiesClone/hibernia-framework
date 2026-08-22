package io.paradaux.hibernia.framework.usher;

import java.util.Objects;

/**
 * A renderer-agnostic description of a dialog button: a label, optional tooltip, width, and the action
 * taken when it is clicked.
 *
 * <p>The action is either a named {@link io.paradaux.hibernia.framework.usher.annotations.Action @Action}
 * method on the handler ({@link Kind#ACTION}), or a built-in navigation operation
 * ({@link Kind#CLOSE}, {@link Kind#BACK}, {@link Kind#OPEN}). Navigation buttons do <em>not</em> read
 * the screen's inputs; a button that must persist input values before navigating should target an
 * {@code @Action} method that reads them and then calls {@link DialogFlow#back()}/{@link DialogFlow#open(String)}.</p>
 *
 * <p>An {@link Kind#ACTION} button may also carry an {@link #argument()} — a string handed to
 * the handler through an {@code @ActionArg} parameter. That is what makes a runtime-sized row of
 * buttons possible: a screen built from configuration or query results can give every button the
 * same {@code @Action} target and tell them apart by their argument, where otherwise each button
 * would need its own statically-named handler method.</p>
 *
 * @param label    the button label
 * @param tooltip  the hover tooltip, or {@code null}
 * @param width    the button width in pixels, or {@code 0} for the client default
 * @param kind     what clicking does
 * @param target   the {@code @Action} name ({@link Kind#ACTION}) or screen name ({@link Kind#OPEN}); else ignored
 * @param argument the value delivered to the action's {@code @ActionArg} parameter, or {@code null}
 */
public record ButtonSpec(Text label, Text tooltip, int width, Kind kind, String target, String argument) {

    public enum Kind { ACTION, CLOSE, BACK, OPEN }

    public ButtonSpec {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.ACTION || kind == Kind.OPEN) && (target == null || target.isBlank())) {
            throw new IllegalArgumentException(kind + " button requires a target name");
        }
    }

    /** A button that invokes the {@code @Action(action)} method on click. */
    public static ButtonSpec action(Text label, String action) {
        return new ButtonSpec(label, null, 0, Kind.ACTION, action, null);
    }

    /**
     * A button that invokes {@code @Action(action)} and hands it {@code argument} through an
     * {@code @ActionArg} parameter — for one-of-many buttons built at render time.
     */
    public static ButtonSpec action(Text label, String action, String argument) {
        return new ButtonSpec(label, null, 0, Kind.ACTION, action, argument);
    }

    /** A button that closes the dialog. */
    public static ButtonSpec close(Text label) {
        return new ButtonSpec(label, null, 0, Kind.CLOSE, null, null);
    }

    /** A button that returns to the previous screen in the flow. */
    public static ButtonSpec back(Text label) {
        return new ButtonSpec(label, null, 0, Kind.BACK, null, null);
    }

    /** A button that opens another screen of the same handler. */
    public static ButtonSpec open(Text label, String screen) {
        return new ButtonSpec(label, null, 0, Kind.OPEN, screen, null);
    }

    public ButtonSpec withTooltip(Text tooltip) {
        return new ButtonSpec(label, tooltip, width, kind, target, argument);
    }

    public ButtonSpec withWidth(int width) {
        return new ButtonSpec(label, tooltip, width, kind, target, argument);
    }
}
