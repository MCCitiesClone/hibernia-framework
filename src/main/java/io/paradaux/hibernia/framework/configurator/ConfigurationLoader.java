package io.paradaux.hibernia.framework.configurator;

import com.google.inject.Singleton;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Discovers {@code @ConfigurationComponent} classes on the classpath,
 * instantiates them and injects their {@code @ConfigurationValue} fields from
 * the plugin's configuration. Instances are singletons, intended to be
 * bound into Guice (which {@code HiberniaModule} does automatically).
 *
 * <h2>Multiple files</h2>
 * <p>A component reads {@code config.yml} unless it names another file via
 * {@link ConfigurationComponent#file()}. Named files live in the plugin's data folder, are
 * copied out of the jar on first run if absent, and are re-read by {@link #reload()} along
 * with {@code config.yml}. This lets a plugin keep several focused operator-facing files
 * instead of one large one.</p>
 *
 * <h2>Atomic reload</h2>
 * <p>{@link #reload()} does <strong>not</strong> mutate live component instances in
 * place. It builds a fresh, fully-populated instance of every component, then
 * publishes the whole set with a single volatile reference swap. A concurrent reader
 * therefore always sees a consistent, all-or-nothing snapshot — never a half-updated
 * POJO — which removes the config-reload visibility race without each consumer having
 * to add its own {@code volatile}/atomic guard.</p>
 *
 * <p>Read the current values through {@link #getComponent(Class)} (the snapshot
 * accessor). A component reference captured earlier — e.g. one Guice injected at
 * startup — keeps showing the values it was loaded with: a consistent <em>stale</em>
 * snapshot, not a torn read. Re-fetch via {@link #getComponent(Class)} to observe a
 * reload.</p>
 */
@Singleton
public class ConfigurationLoader {

    /** The component file name that means "the plugin's own config.yml". */
    public static final String DEFAULT_FILE = "config.yml";

    private final JavaPlugin plugin;
    private final ConfigurationProcessor processor;
    /** Component classes that have loaded at least once, in discovery order; rebuilt on reload. */
    private final List<Class<?>> componentClasses = new ArrayList<>();
    /** Loaded auxiliary files, keyed by file name. {@code config.yml} is not held here. */
    private final Map<String, FileConfiguration> auxiliaryFiles = new LinkedHashMap<>();
    /** The current immutable snapshot of loaded components; swapped atomically on reload. */
    private volatile Map<Class<?>, Object> components = Map.of();

    public ConfigurationLoader(JavaPlugin plugin) {
        this.plugin = plugin;
        this.processor = new ConfigurationProcessor(plugin);

        // Ensure config.yml exists. Bukkit's saveDefaultConfig() throws when the jar packages no
        // config.yml, which is a legitimate layout now that components can each name their own
        // file — such a plugin may have no config.yml at all. Absence is not an error here.
        try {
            plugin.saveDefaultConfig();
        } catch (IllegalArgumentException noPackagedConfig) {
            plugin.getLogger().fine("No packaged config.yml; components must name their own files");
        }
    }

    /**
     * Scan package for components and load their configurations
     */
    public void scanPackage(String packageName) {
        Reflections reflections = new Reflections(packageName);
        Set<Class<?>> componentClasses = reflections.getTypesAnnotatedWith(ConfigurationComponent.class);

        Map<Class<?>, Object> updated = new LinkedHashMap<>(components);
        for (Class<?> componentClass : componentClasses) {
            try {
                Object instance = build(componentClass);
                updated.put(componentClass, instance);
                if (!this.componentClasses.contains(componentClass)) {
                    this.componentClasses.add(componentClass);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to instantiate component: " + componentClass.getName(), e);
            }
        }
        this.components = Map.copyOf(updated);
    }

    /**
     * Get a component by class.
     *
     * @throws IllegalStateException when no component of that class was loaded —
     *         either {@code scanPackage(...)} never covered its package, or the
     *         component failed to load (see the startup log)
     */
    public <T> T getComponent(Class<T> componentClass) {
        Object component = components.get(componentClass);
        if (component == null) {
            throw new IllegalStateException("No @ConfigurationComponent loaded for "
                    + componentClass.getName() + " — check that scanPackage(...) covers its package"
                    + " and that it instantiated without errors (see startup log).");
        }
        return componentClass.cast(component);
    }

    /** The current component snapshot (immutable). */
    public Map<Class<?>, Object> getComponents() {
        return components;
    }

    /**
     * The parsed contents of an auxiliary configuration file, loading it if this is the first
     * request. Useful for reading a file that no component maps in full.
     *
     * @param fileName a YAML file in the plugin's data folder
     * @return its configuration, empty if the file is absent from both disk and the jar
     */
    public FileConfiguration getFile(String fileName) {
        if (DEFAULT_FILE.equals(fileName)) {
            return plugin.getConfig();
        }
        return auxiliaryFiles.computeIfAbsent(fileName, this::loadFile);
    }

    /**
     * Re-read every configuration file from disk and rebuild each loaded component into a
     * fresh instance, then publish the whole set atomically. Readers going through
     * {@link #getComponent(Class)} switch from the old snapshot to the new one in a
     * single step — they never observe a partially-updated component. A component that
     * fails to rebuild keeps its last good values rather than dropping out.
     */
    public void reload() {
        plugin.reloadConfig();
        // Drop the cache so each auxiliary file is re-read on next use; a component whose file
        // was edited must not keep binding from the copy parsed at startup.
        auxiliaryFiles.clear();

        Map<Class<?>, Object> previous = components;
        Map<Class<?>, Object> rebuilt = new LinkedHashMap<>();
        for (Class<?> componentClass : componentClasses) {
            try {
                rebuilt.put(componentClass, build(componentClass));
            } catch (Exception e) {
                Object prev = previous.get(componentClass);
                if (prev != null) {
                    rebuilt.put(componentClass, prev);
                }
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to reload component: " + componentClass.getName(), e);
            }
        }
        this.components = Map.copyOf(rebuilt);
    }

    /** Instantiate a component and bind it from whichever file and root path it declares. */
    private Object build(Class<?> componentClass) throws Exception {
        Object instance = instantiate(componentClass);
        processor.process(instance, sectionFor(componentClass));
        return instance;
    }

    /**
     * The section a component binds from: its file's root, or the subsection named by
     * {@link ConfigurationComponent#path()}. A declared-but-absent root path binds against an
     * empty section so the component still loads on its defaults rather than failing outright.
     */
    private ConfigurationSection sectionFor(Class<?> componentClass) {
        ConfigurationComponent annotation = componentClass.getAnnotation(ConfigurationComponent.class);
        String fileName = annotation == null ? DEFAULT_FILE : annotation.file();
        FileConfiguration file = getFile(fileName);

        String root = annotation == null ? "" : annotation.path();
        if (root == null || root.isEmpty()) {
            return file;
        }
        ConfigurationSection section = file.getConfigurationSection(root);
        if (section != null) {
            return section;
        }
        plugin.getLogger().warning("Configuration path '" + root + "' not found in " + fileName
                + " for " + componentClass.getSimpleName() + "; using defaults");
        return new YamlConfiguration();
    }

    /**
     * Loads an auxiliary file, first copying the jar's packaged copy into the data folder when
     * the operator has none — the same first-run behaviour {@code config.yml} gets.
     */
    private FileConfiguration loadFile(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException noSuchResource) {
                // No packaged default: an empty configuration is the right answer, and the
                // component falls back to its annotation defaults.
                plugin.getLogger().warning("No packaged default for configuration file '" + fileName
                        + "'; components reading it will use their declared defaults");
                return new YamlConfiguration();
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private Object instantiate(Class<?> componentClass) throws Exception {
        Constructor<?> constructor = componentClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
