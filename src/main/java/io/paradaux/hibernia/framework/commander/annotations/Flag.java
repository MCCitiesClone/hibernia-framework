package io.paradaux.hibernia.framework.commander.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter as a named command flag — {@code --name value}, or
 * {@code --name} on its own for a {@linkplain #presence() presence} flag.
 *
 * <p>Flags do <em>not</em> appear in the {@code @Route} pattern. They are accepted in any
 * order after the route's positional segments, so a single route supports every
 * combination of its flags without declaring one route per combination:</p>
 *
 * <pre>
 * &#64;Route("list [region]")
 * public void list(&#64;Sender Player sender,
 *                  &#64;OptionalArg("region") Region region,
 *                  &#64;Flag("page") Integer page,
 *                  &#64;Flag(value = "mine", presence = true) boolean mine) { ... }
 *
 * // /x list            /x list --page 2
 * // /x list --mine     /x list spawn --page 3 --mine
 * </pre>
 *
 * <p>Values resolve through the same {@code ParameterResolver} registry as positional
 * arguments, so a flag typed to a domain class tab-completes exactly as a {@code <arg>}
 * of that class would. Both {@code --name value} and {@code --name=value} are accepted,
 * and a value may be double-quoted to include spaces.</p>
 *
 * <p>An omitted flag yields {@link #defaultValue()} when one is set, {@code null}
 * otherwise ({@code false} for a presence flag). A primitive-typed value flag therefore
 * requires a {@code defaultValue}; this is enforced at registration time.</p>
 *
 * @see io.paradaux.hibernia.framework.commander.CommandManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Flag {

    /**
     * The flag's name, written by the user as {@code --name}. Leading dashes are
     * optional here and stripped: {@code @Flag("page")} and {@code @Flag("--page")}
     * declare the same flag.
     *
     * @return the flag name
     */
    String value();

    /**
     * Additional names accepted for this flag, e.g. {@code aliases = "p"} to accept
     * {@code --p}. Aliases share the declared flag's type and default.
     *
     * @return the alias names
     */
    String[] aliases() default {};

    /**
     * A string form of the value to use when the flag is absent, resolved to the
     * parameter type through the registered resolvers. Empty means "no default": the
     * parameter receives {@code null} ({@code ""} for a String parameter).
     *
     * @return the default value as a string
     */
    String defaultValue() default "";

    /**
     * Whether this flag is a bare switch that takes no value. A presence flag's
     * parameter must be {@code boolean}/{@link Boolean}, and is {@code true} exactly
     * when the flag was supplied.
     *
     * @return {@code true} if the flag takes no value
     */
    boolean presence() default false;

    /**
     * Whether to sanitize the supplied value (strip MiniMessage tags and risky
     * punctuation). Defaults to {@code true}; set {@code false} for values that
     * legitimately contain such characters, such as URLs.
     *
     * @return {@code true} if the value should be sanitized
     */
    boolean sanitize() default true;
}
