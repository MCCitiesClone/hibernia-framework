package io.paradaux.hibernia.framework.commander.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter as a required command argument.
 *
 * <p>Used by the CommandManager to bind a named placeholder from a route
 * (e.g. &lt;player&gt;) to a method parameter.</p>
 *
 * <p>Example:
 * <pre>
 * @Route("give &lt;player&gt; &lt;amount&gt;")
 * public void give(@Arg("player") OfflinePlayer player, @Arg("amount") int amount) { ... }
 * </pre>
 * </p>
 *
 * @see io.paradaux.hibernia.framework.commander.CommandManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Arg {
    /**
     * The name of the argument as used in the route placeholder (without &lt;&gt;).
     *
     * @return the argument name
     */
    String value(); // name

    /**
     * Whether to sanitize the argument value (strip MiniMessage tags, special characters, etc.).
     *
     * <p>Defaults to {@code true}. Set to {@code false} when the argument may contain
     * special characters such as URLs (e.g. {@code https://example.com}).</p>
     *
     * @return {@code true} if the value should be sanitized
     */
    boolean sanitize() default true;

    /**
     * Inclusive lower bound for a numeric argument. Applied to the Brigadier node, so the client
     * refuses an out-of-range value before it is sent rather than the handler having to check.
     *
     * @return the minimum, or {@link Double#NEGATIVE_INFINITY} for unbounded
     */
    double min() default Double.NEGATIVE_INFINITY;

    /**
     * Inclusive upper bound for a numeric argument.
     *
     * @return the maximum, or {@link Double#POSITIVE_INFINITY} for unbounded
     */
    double max() default Double.POSITIVE_INFINITY;
}
