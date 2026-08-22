package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.reloadprobe.ReloadBomb;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ConfigurationLoader#reload()}'s failure path: a component that fails to rebuild keeps
 * its last good instance in the published snapshot rather than dropping out.
 */
class ConfigurationLoaderReloadTest {

    private JavaPlugin plugin;
    private Logger logger;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        logger = mock(Logger.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @Test
    void reload_componentThatFailsToRebuild_keepsLastGoodInstance() {
        ReloadBomb.armed = false;
        try {
            ConfigurationLoader loader = new ConfigurationLoader(plugin);
            loader.scanPackage("io.paradaux.hibernia.framework.reloadprobe");
            ReloadBomb first = loader.getComponent(ReloadBomb.class);

            // Arm the constructor so the rebuild during reload() throws.
            ReloadBomb.armed = true;
            loader.reload();

            verify(plugin).reloadConfig();
            // The component survived with its previous instance instead of vanishing.
            assertSame(first, loader.getComponent(ReloadBomb.class));
            verify(logger).log(eq(Level.SEVERE), contains("Failed to reload"), any(Throwable.class));
        } finally {
            ReloadBomb.armed = false;
        }
    }
}
