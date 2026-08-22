package io.paradaux.hibernia.framework.commander.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter as an optional command argument with a default value.
 *
 * <p>Optional arguments appear in the route in square brackets and must form the
 * tail of the route. The command is executable both with and without the optional
 * tail; when omitted, the {@code defaultValue} applies. If the argument is omitted
 * and no {@code defaultValue} is given, the parameter receives {@code null}
 * (primitive parameter types therefore require a {@code defaultValue} — this is
 * enforced at registration time).</p>
 *
 * <p>The CommandManager resolves the string default to the target parameter type
 * using the registered {@code ParameterResolver}s. The {@link #SENDER} sentinel
 * defaults the parameter to the command sender, when assignable.</p>
 *
 * <p>Example:
 * <pre>
 * @Route("balance [player]")
 * public void balance(@Sender Player sender,
 *                     @OptionalArg(value = "player", defaultValue = OptionalArg.SENDER) OfflinePlayer player) { ... }
 *
 * @Route("top [page]")
 * public void top(@OptionalArg(value = "page", defaultValue = "1") int page) { ... }
 * </pre>
 * </p>
 *
 * @see io.paradaux.hibernia.framework.commander.CommandManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OptionalArg {

    /**
     * Sentinel {@code defaultValue} that injects the command sender itself when the
     * argument is omitted. The parameter type must be assignable from the runtime
     * sender (e.g. an {@code OfflinePlayer} parameter when a player runs the
     * command); otherwise the sender is told to supply the argument explicitly.
     */
    String SENDER = "@sender";

    /**
     * The name of the argument as used in the route placeholder.
     *
     * @return the argument name
     */
    String value(); // name

    /**
     * A string form of the default value to use when the argument is not provided.
     * The framework will attempt to resolve this value to the parameter type.
     * Use {@link #SENDER} to default to the command sender. An empty string means
     * "no default": the parameter receives {@code null} ({@code ""} for String
     * parameters).
     *
     * @return the default value as string
     */
    String defaultValue() default "";

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
