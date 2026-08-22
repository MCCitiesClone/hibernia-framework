package io.paradaux.hibernia.framework.usher.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the clicked button's argument into an {@code @Action} parameter.
 *
 * <p>{@code @Action} methods are looked up by name, so without an argument every button needs
 * its own statically-named handler. That is impossible for a row of buttons built at render
 * time from configuration or query results — the set is not known when the class is written.
 * An argument lets each of those buttons share one action and say which item it stands for:</p>
 *
 * <pre>
 * &#64;Screen("tags")
 * public DialogView tags(&#64;Model Filter filter) {
 *     DialogView.Builder view = DialogView.multiAction("filter.title");
 *     for (Tag tag : tags.all()) {                        // runtime-sized
 *         view.button(ButtonSpec.action(Text.of(tag.label()), "cycleTag", tag.id()));
 *     }
 *     return view.build();
 * }
 *
 * &#64;Action("cycleTag")
 * public void cycleTag(&#64;ActionArg String tagId, &#64;Model Filter filter, DialogFlow flow) {
 *     filter.cycle(tagId);
 *     flow.refresh();
 * }
 * </pre>
 *
 * <p>Supported parameter types are {@code String}, the boxed and primitive numeric types,
 * {@code boolean} and any enum. A button that carries no argument delivers {@code null}
 * (and fails for a primitive parameter, which cannot represent "absent").</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ActionArg {
}
