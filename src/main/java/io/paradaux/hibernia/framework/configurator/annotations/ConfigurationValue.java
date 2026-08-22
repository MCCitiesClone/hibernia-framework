package io.paradaux.hibernia.framework.configurator.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for reflective injection from {@code config.yml}.
 *
 * <p>Supported field types: {@code String}, {@code int}/{@code Integer},
 * {@code long}/{@code Long}, {@code double}/{@code Double}, {@code float}/{@code Float},
 * {@code boolean}/{@code Boolean}, {@link java.math.BigDecimal}, {@link java.util.List}
 * and enums.</p>
 *
 * <p><strong>Money:</strong> use {@link java.math.BigDecimal}, never {@code double}/{@code float}.
 * Binary floating point can't represent most decimal amounts exactly, so currency parsed as a
 * {@code double} accumulates rounding error. {@code BigDecimal} reads the raw value losslessly.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigurationValue {
    /**
     * The path to the configuration value in the YAML file
     */
    String path();

    /**
     * Optional default value if the path doesn't exist
     */
    String defaultValue() default "";
}
