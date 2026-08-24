package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Binds YAML onto annotated POJOs.
 *
 * <p>Binding is recursive and section-relative: every step works against a Bukkit
 * {@link ConfigurationSection} rather than the file root, which is what lets a
 * {@link ConfigurationObject} nested inside a list, a map or another object be populated by
 * the same code that populates a top-level component.</p>
 *
 * <p>Supported field types: {@code String}, the boxed and primitive numeric types,
 * {@code boolean}, {@link BigDecimal}, {@link UUID}, {@link Component} (parsed as MiniMessage),
 * enums, {@code List<T>}, {@code Set<T>}, {@code Map<K, V>} and any {@link ConfigurationObject}
 * — with {@code T}, {@code K} and {@code V} themselves drawn from that same set.</p>
 *
 * <h2>Records</h2>
 * <p>A component or object may be a {@code record}. Records cannot be field-injected — their
 * fields are final — so they are <em>constructed</em> instead: each component is read from the
 * section and handed to the canonical constructor. That is the more faithful binding of the
 * two, because a record's compact constructor runs as part of it, so validation and default
 * normalisation written there apply to configured values exactly as they do to code-built
 * ones.</p>
 */
public class ConfigurationProcessor {

    /** Depth guard: an anchor-built recursive graph would otherwise recurse until the stack ends. */
    private static final int MAX_DEPTH = 16;

    private final Plugin plugin;

    public ConfigurationProcessor(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Inject configuration values into {@code target} from the plugin's {@code config.yml}.
     */
    public void process(Object target) {
        process(target, plugin.getConfig());
    }

    /**
     * Inject configuration values into {@code target} from an arbitrary section — a file
     * root, or a subsection when binding a nested object.
     *
     * <p>Mutable types only. Use {@link #create(Class, ConfigurationSection)} to bind a type that
     * may be a record.</p>
     */
    public void process(Object target, ConfigurationSection section) {
        bindFields(target, section, new ArrayDeque<>());
    }

    /**
     * Build an instance of {@code type} from {@code section} — constructing it if it is a record,
     * otherwise instantiating and field-injecting it.
     */
    public Object create(Class<?> type, ConfigurationSection section) {
        return bindObject(type, section, new ArrayDeque<>());
    }

    private void bindFields(Object target, ConfigurationSection section, Deque<Class<?>> stack) {
        Class<?> clazz = target.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            ConfigurationValue annotation = field.getAnnotation(ConfigurationValue.class);
            if (annotation == null) {
                continue;
            }
            String path = annotation.path();
            try {
                if (!field.trySetAccessible()) {
                    plugin.getLogger().warning("Cannot access field: " + field.getName() + " - skipping");
                    continue;
                }
                if (Modifier.isFinal(field.getModifiers())) {
                    plugin.getLogger().warning("Cannot inject config into final field: " + field.getName());
                    continue;
                }

                Object value = readValue(section, path, annotation.defaultValue(),
                        field.getType(), field.getGenericType(), stack);
                if (value != null) {
                    field.set(target, value);
                }
            } catch (Exception e) {
                // A bad value (unparseable number, unknown enum constant, type mismatch) must
                // name the path and field, with the cause attached — not vanish into a generic
                // message.
                plugin.getLogger().log(Level.WARNING,
                        "Failed to inject config value '" + path + "' into "
                                + clazz.getSimpleName() + "." + field.getName() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Reads one value at {@code path}, dispatching on the declared type. {@code generic}
     * carries the field's parameterised type so element types of lists and maps survive
     * erasure.
     */
    private Object readValue(ConfigurationSection section, String path, String defaultValue,
                             Class<?> type, Type generic, Deque<Class<?>> stack) {
        if (isConfigurationObject(type)) {
            ConfigurationSection child = section.getConfigurationSection(path);
            if (child == null) {
                // A missing optional object is a legitimate "not configured", not an error —
                // a config that ships every section commented out is normal.
                return null;
            }
            return bindObject(type, child, stack);
        }

        if (type == List.class) {
            return readList(section, path, generic, stack);
        }

        if (type == Set.class) {
            Object list = readList(section, path, generic, stack);
            return list instanceof List<?> values
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(values))
                    : Set.of();
        }

        if (type == Map.class) {
            return readMap(section, path, generic, stack);
        }

        return readScalar(section, path, defaultValue, type);
    }

    private Object readList(ConfigurationSection section, String path, Type generic, Deque<Class<?>> stack) {
        Class<?> element = typeArgument(generic, 0);
        if (element == null || !isConfigurationObject(element)) {
            // Scalar list: Bukkit's getStringList is the historical behaviour and stays.
            List<String> raw = section.getStringList(path);
            if (element == null || element == String.class || element == Object.class) {
                return raw;
            }
            // A typed scalar list -- List<UUID>, List<Integer> and friends. getStringList alone
            // hands back Strings, and erasure lets the record field hold them: nothing fails here,
            // but every later lookup against the declared type silently misses. Coerce so the list
            // actually contains what its type says it does.
            List<Object> converted = new ArrayList<>(raw.size());
            for (String value : raw) {
                Object coerced = coerceElement(value, element, path);
                if (coerced != null) {
                    converted.add(coerced);
                }
            }
            return converted;
        }
        List<?> raw = section.getList(path);
        if (raw == null) {
            return List.of();
        }
        List<Object> bound = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            ConfigurationSection entry = asSection(section, path + "[" + i + "]", raw.get(i));
            if (entry != null) {
                bound.add(bindObject(element, entry, stack));
            }
        }
        return List.copyOf(bound);
    }

    /**
     * Converts one entry of a scalar list to the list's declared element type. A value that cannot
     * be converted is warned about and dropped rather than failing the whole component: one bad
     * UUID in a long exemption list should not cost the operator every other entry.
     */
    private Object coerceElement(String value, Class<?> element, String path) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (element == UUID.class) {
                return UUID.fromString(trimmed);
            } else if (element == Integer.class || element == int.class) {
                return Integer.valueOf(trimmed);
            } else if (element == Long.class || element == long.class) {
                return Long.valueOf(trimmed);
            } else if (element == Double.class || element == double.class) {
                return Double.valueOf(trimmed);
            } else if (element == Float.class || element == float.class) {
                return Float.valueOf(trimmed);
            } else if (element == Boolean.class || element == boolean.class) {
                return Boolean.valueOf(trimmed);
            } else if (element == BigDecimal.class) {
                return new BigDecimal(trimmed);
            } else if (element == Component.class) {
                return MiniMessage.miniMessage().deserialize(value);
            } else if (element.isEnum()) {
                Object constant = matchEnum(element, trimmed);
                if (constant == null) {
                    throw new IllegalArgumentException(
                            "expected one of " + Arrays.toString(element.getEnumConstants()));
                }
                return constant;
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Ignoring invalid " + element.getSimpleName() + " '" + value
                    + "' in " + path + ": " + ex.getMessage());
            return null;
        }
        // An element type the processor has no conversion for; hand back the raw text unchanged.
        return value;
    }

    private Object readMap(ConfigurationSection section, String path, Type generic, Deque<Class<?>> stack) {
        ConfigurationSection child = section.getConfigurationSection(path);
        if (child == null) {
            return Map.of();
        }
        Class<?> keyType = typeArgument(generic, 0);
        Class<?> valueType = typeArgument(generic, 1);

        Map<Object, Object> bound = new LinkedHashMap<>();
        for (String key : child.getKeys(false)) {
            Object mapKey = convertMapKey(key, keyType, path);
            if (mapKey == null) {
                continue;
            }
            Object mapValue;
            if (valueType != null && isConfigurationObject(valueType)) {
                ConfigurationSection valueSection = child.getConfigurationSection(key);
                mapValue = valueSection == null ? null : bindObject(valueType, valueSection, stack);
            } else {
                mapValue = readScalar(child, key, "", valueType == null ? String.class : valueType);
            }
            if (mapValue != null) {
                bound.put(mapKey, mapValue);
            }
        }
        return Collections.unmodifiableMap(bound);
    }

    /** Map keys arrive as YAML strings; an enum-keyed map converts them, case-insensitively. */
    private Object convertMapKey(String key, Class<?> keyType, String path) {
        if (keyType == null || keyType == String.class) {
            return key;
        }
        if (keyType.isEnum()) {
            Object constant = matchEnum(keyType, key);
            if (constant == null) {
                plugin.getLogger().warning("Invalid key '" + key + "' under " + path
                        + "; expected one of " + Arrays.toString(keyType.getEnumConstants()));
            }
            return constant;
        }
        if (keyType == Integer.class) {
            try {
                return Integer.valueOf(key.trim());
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid integer key '" + key + "' under " + path);
                return null;
            }
        }
        return key;
    }

    /**
     * A YAML list entry arrives as a {@code Map}, not a {@link ConfigurationSection}. Wrap it
     * in a detached section so the same object binder handles it.
     */
    private ConfigurationSection asSection(ConfigurationSection parent, String where, Object entry) {
        if (entry instanceof ConfigurationSection alreadySection) {
            return alreadySection;
        }
        if (entry instanceof Map<?, ?> map) {
            String temporaryKey = "hibernia$tmp$" + Integer.toHexString(System.identityHashCode(entry));
            // createSection(path, map) converts nested maps into real sections all the way
            // down; plain set() would store them as raw Maps that getConfigurationSection
            // cannot see, silently nulling every object nested inside a list entry.
            ConfigurationSection wrapper = parent.createSection(temporaryKey, map);
            // Detach immediately: the wrapper exists only to bind from, and must never
            // survive into a save of the operator's file.
            parent.set(temporaryKey, null);
            return wrapper;
        }
        plugin.getLogger().warning("Expected a mapping at " + where + " but found "
                + (entry == null ? "nothing" : entry.getClass().getSimpleName()));
        return null;
    }

    private Object bindObject(Class<?> type, ConfigurationSection section, Deque<Class<?>> stack) {
        // A type may legitimately appear at more than one depth — a tree of like-typed
        // groups is ordinary config — so recursion is bounded by depth, not by type. YAML
        // parsed from a file is a finite tree; the guard exists for anchor-built graphs.
        if (stack.size() >= MAX_DEPTH) {
            throw new IllegalStateException("Configuration nesting deeper than " + MAX_DEPTH
                    + " at " + type.getSimpleName() + " (" + describe(stack) + ")");
        }
        stack.push(type);
        try {
            return type.isRecord()
                    ? bindRecord(type, section, stack)
                    : bindMutable(type, section, stack);
        } finally {
            stack.pop();
        }
    }

    private Object bindMutable(Class<?> type, ConfigurationSection section, Deque<Class<?>> stack) {
        Object instance;
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName()
                    + " needs an accessible no-argument constructor", e);
        }
        bindFields(instance, section, stack);
        return instance;
    }

    /**
     * Builds a record by reading each component and invoking the canonical constructor, so the
     * record's own compact constructor gets to validate and normalise the configured values.
     */
    private Object bindRecord(Class<?> type, ConfigurationSection section, Deque<Class<?>> stack) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            ConfigurationValue annotation = annotationOf(type, component);
            Object value = null;
            if (annotation != null) {
                String path = annotation.path().isEmpty() ? component.getName() : annotation.path();
                try {
                    value = readValue(section, path, annotation.defaultValue(),
                            component.getType(), component.getGenericType(), stack);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to read config value '" + path + "' for "
                                    + type.getSimpleName() + "." + component.getName()
                                    + ": " + e.getMessage(), e);
                }
            }
            arguments[i] = value != null ? value : defaultFor(component.getType());
        }

        try {
            Constructor<?> canonical = type.getDeclaredConstructor(parameterTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                    ? ite.getTargetException() : e;
            throw new IllegalStateException("Failed to construct record " + type.getName()
                    + ": " + cause.getMessage(), cause);
        }
    }

    /**
     * A record component's {@code @ConfigurationValue}, taken from the component declaration or,
     * failing that, the backing field — which of the two carries it depends on the annotation's
     * declared targets.
     */
    private static ConfigurationValue annotationOf(Class<?> type, RecordComponent component) {
        ConfigurationValue annotation = component.getAnnotation(ConfigurationValue.class);
        if (annotation != null) {
            return annotation;
        }
        try {
            return type.getDeclaredField(component.getName()).getAnnotation(ConfigurationValue.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * The value handed to a canonical constructor for an unconfigured component. A primitive
     * cannot take null, and its zero value is what a record's compact constructor is written to
     * normalise anyway.
     */
    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        return null;
    }

    private static String describe(Deque<Class<?>> stack) {
        List<String> names = new ArrayList<>();
        for (Class<?> type : stack) {
            names.add(type.getSimpleName());
        }
        return String.join(" -> ", names);
    }

    private static boolean isConfigurationObject(Class<?> type) {
        return type.isAnnotationPresent(ConfigurationObject.class);
    }

    /** The {@code index}-th type argument of a parameterised field type, or null when erased. */
    private static Class<?> typeArgument(Type generic, int index) {
        if (!(generic instanceof ParameterizedType parameterized)) {
            return null;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (index >= arguments.length) {
            return null;
        }
        return arguments[index] instanceof Class<?> raw ? raw : null;
    }

    @SuppressWarnings("unchecked")
    private Object readScalar(ConfigurationSection config, String path, String defaultValue, Class<?> type) {
        if (!config.contains(path) && defaultValue.isEmpty()) {
            plugin.getLogger().warning("Configuration path not found: " + path);
            return null;
        }

        if (type == String.class) {
            return config.getString(path, defaultValue);
        } else if (type == int.class || type == Integer.class) {
            return config.contains(path) ? config.getInt(path) : Integer.parseInt(defaultValue);
        } else if (type == boolean.class || type == Boolean.class) {
            return config.contains(path) ? config.getBoolean(path) : Boolean.parseBoolean(defaultValue);
        } else if (type == double.class || type == Double.class) {
            return config.contains(path) ? config.getDouble(path) : Double.parseDouble(defaultValue);
        } else if (type == float.class || type == Float.class) {
            return config.contains(path) ? (float) config.getDouble(path) : Float.parseFloat(defaultValue);
        } else if (type == long.class || type == Long.class) {
            return config.contains(path) ? config.getLong(path) : Long.parseLong(defaultValue);
        } else if (type == BigDecimal.class) {
            // Money and other exact-decimal values: never route through double. Read the raw scalar
            // (string or YAML number) and parse it losslessly, so 0.1 + 0.2 stays 0.3.
            return parseBigDecimal(config, path, defaultValue);
        } else if (type == UUID.class) {
            String raw = config.getString(path, defaultValue);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return UUID.fromString(raw.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid UUID '" + raw + "' for " + path);
            }
        } else if (type == Component.class) {
            String raw = config.getString(path, defaultValue);
            return raw == null ? null : MiniMessage.miniMessage().deserialize(raw);
        } else if (type == List.class) {
            return config.getStringList(path);
        } else if (type.isEnum()) {
            String value = config.getString(path, defaultValue);
            // Matched case-insensitively on purpose: YAML reads unquoted TRUE/FALSE/ON/OFF as
            // booleans, which Bukkit renders back as "true"/"false", so a strict valueOf would
            // reject `default: TRUE` against a constant named TRUE.
            Object constant = matchEnum(type, value);
            if (constant == null) {
                throw new IllegalArgumentException("Invalid value '" + value + "' for " + path
                        + "; expected one of " + Arrays.toString(type.getEnumConstants()));
            }
            return constant;
        }

        // For complex types, return the object directly
        return config.get(path);
    }

    /** Case-insensitive enum constant lookup; null when no constant matches. */
    private static Object matchEnum(Class<?> type, String value) {
        if (value == null) {
            return null;
        }
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        return null;
    }

    /**
     * Parse a {@link BigDecimal} from the raw config scalar (a string, or a YAML
     * number coerced via {@code toString()}), falling back to the annotation
     * default. A malformed value names the path so the operator can find it.
     */
    private BigDecimal parseBigDecimal(ConfigurationSection config, String path, String defaultValue) {
        String raw;
        if (config.contains(path)) {
            Object value = config.get(path);
            raw = value == null ? defaultValue : value.toString();
        } else {
            raw = defaultValue;
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid BigDecimal value '" + raw + "' for " + path);
        }
    }
}
