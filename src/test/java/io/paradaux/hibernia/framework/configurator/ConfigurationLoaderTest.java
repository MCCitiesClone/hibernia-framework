package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigurationLoaderTest {

    private JavaPlugin plugin;
    private FileConfiguration config;
    private Logger logger;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        config = mock(FileConfiguration.class);
        logger = mock(Logger.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @ConfigurationComponent
    public static class GoodComponent {
        @ConfigurationValue(path = "loader.value", defaultValue = "fallback")
        String value;
    }

    @ConfigurationComponent
    public static class FailingComponent {
        private FailingComponent(String ignored) {
        }
    }

    @Test
    void constructor_savesDefaultConfig() {
        new ConfigurationLoader(plugin);

        verify(plugin).saveDefaultConfig();
    }

    @Test
    void scanPackage_registersAndProcessesAnnotatedComponents() {
        when(config.contains("loader.value")).thenReturn(true);
        when(config.getString("loader.value", "fallback")).thenReturn("loaded");

        ConfigurationLoader loader = new ConfigurationLoader(plugin);
        loader.scanPackage("io.paradaux.hibernia.framework.configurator");

        GoodComponent component = loader.getComponent(GoodComponent.class);
        assertNotNull(component);
        assertEquals("loaded", component.value);
        assertTrue(loader.getComponents().containsKey(GoodComponent.class));
    }

    @Test
    void scanPackage_logsFailureWithCauseWhenInstantiationFails() {
        ConfigurationLoader loader = new ConfigurationLoader(plugin);

        loader.scanPackage("io.paradaux.hibernia.framework.configurator");

        // The cause must be attached, not swallowed into a bare message.
        verify(logger).log(eq(Level.SEVERE), contains(FailingComponent.class.getName()), any(Throwable.class));
    }

    @Test
    void getComponent_throwsWithDiagnosticWhenComponentMissing() {
        ConfigurationLoader loader = new ConfigurationLoader(plugin);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loader.getComponent(GoodComponent.class));
        assertTrue(ex.getMessage().contains("scanPackage"));
    }

    @Test
    void reload_swapsInFreshlyPopulatedSnapshotAtomically() {
        when(config.contains("loader.value")).thenReturn(true);
        when(config.getString("loader.value", "fallback")).thenReturn("loaded", "reloaded");

        ConfigurationLoader loader = new ConfigurationLoader(plugin);
        loader.scanPackage("io.paradaux.hibernia.framework.configurator");

        GoodComponent first = loader.getComponent(GoodComponent.class);
        assertEquals("loaded", first.value);

        loader.reload();

        verify(plugin).reloadConfig();
        GoodComponent afterReload = loader.getComponent(GoodComponent.class);
        // A fresh instance is published atomically; the snapshot reflects the reload.
        assertNotSame(first, afterReload);
        assertEquals("reloaded", afterReload.value);
        // The previously captured reference is a consistent stale snapshot, never torn.
        assertEquals("loaded", first.value);
    }

    @Test
    void reload_keepsLoadedComponentsPresent() {
        when(config.contains("loader.value")).thenReturn(true);
        when(config.getString("loader.value", "fallback")).thenReturn("loaded");

        ConfigurationLoader loader = new ConfigurationLoader(plugin);
        loader.scanPackage("io.paradaux.hibernia.framework.configurator");
        assertEquals("loaded", loader.getComponent(GoodComponent.class).value);

        loader.reload();

        // The component survives a reload and stays resolvable from the new snapshot.
        assertNotNull(loader.getComponent(GoodComponent.class));
        assertTrue(loader.getComponents().containsKey(GoodComponent.class));
    }
}
