package io.paradaux.hibernia.framework.configurator.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a configuration component: the loader instantiates it, injects its
 * {@code @ConfigurationValue} fields and binds the instance as a Guice singleton.
 *
 * <p>By default values come from the plugin's {@code config.yml}. Set {@link #file()} to
 * read a different YAML file in the plugin's data folder instead, which lets a plugin keep
 * several operator-facing files rather than one large one:</p>
 *
 * <pre>
 * &#64;ConfigurationComponent(file = "taxes.yml")
 * public final class TaxConfiguration {
 *     &#64;ConfigurationValue(path = "enabled", defaultValue = "true")
 *     private boolean enabled;
 * }
 * </pre>
 *
 * <p>A named file is copied out of the plugin jar on first run if it is missing, exactly as
 * {@code config.yml} is, and is re-read by {@code ConfigurationLoader.reload()}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigurationComponent {

    /**
     * The YAML file in the plugin's data folder to read from. Defaults to the plugin's
     * {@code config.yml}.
     *
     * @return the file name
     */
    String file() default "config.yml";

    /**
     * An optional path prefix applied to every {@code @ConfigurationValue} path in this
     * component, so a component mapping a nested section need not repeat it on each field.
     *
     * @return the root path, or empty for the file root
     */
    String path() default "";
}
