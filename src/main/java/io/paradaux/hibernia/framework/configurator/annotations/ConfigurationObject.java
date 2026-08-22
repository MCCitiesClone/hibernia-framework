package io.paradaux.hibernia.framework.configurator.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a nested configuration object: a POJO bound from a YAML <em>section</em>
 * rather than from a file root.
 *
 * <p>Unlike {@link ConfigurationComponent}, a configuration object is not discovered, not
 * instantiated on its own and not bound into Guice. It is populated only where a component
 * (or another object) refers to it — as a field type, or as the element type of a
 * {@code List} or {@code Map}:</p>
 *
 * <pre>
 * &#64;ConfigurationObject
 * public final class SignTemplate {
 *     &#64;ConfigurationValue(path = "lines")
 *     private List&lt;String&gt; lines;
 * }
 *
 * &#64;ConfigurationObject
 * public final class RegionProfile {
 *     &#64;ConfigurationValue(path = "priority", defaultValue = "0")
 *     private int priority;
 *
 *     &#64;ConfigurationValue(path = "flags")
 *     private Map&lt;String, String&gt; flags;
 *
 *     &#64;ConfigurationValue(path = "sign")
 *     private SignTemplate sign;
 * }
 *
 * &#64;ConfigurationComponent(file = "profiles.yml")
 * public final class ProfileConfiguration {
 *     &#64;ConfigurationValue(path = "global")
 *     private Map&lt;RegionState, RegionProfile&gt; global;
 * }
 * </pre>
 *
 * <p>Objects need a no-argument constructor, which may be private. Nesting may go as deep as
 * the YAML does; a cycle in the type graph is rejected at load time rather than recursing
 * until the stack runs out.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigurationObject {
}
