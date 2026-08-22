package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Components reading a file other than {@code config.yml}, and the reload path that must
 * re-read those files rather than serving the copy parsed at startup.
 */
class MultiFileConfigurationTest {

    @ConfigurationComponent(file = "taxes.yml")
    static class TaxConfiguration {
        @ConfigurationValue(path = "enabled", defaultValue = "false")
        boolean enabled;

        @ConfigurationValue(path = "government-account", defaultValue = "Treasury")
        String account;
    }

    @ConfigurationComponent
    static class MainConfiguration {
        @ConfigurationValue(path = "name", defaultValue = "unset")
        String name;
    }

    @ConfigurationComponent(file = "nested.yml", path = "database")
    static class RootedConfiguration {
        @ConfigurationValue(path = "host", defaultValue = "localhost")
        String host;
    }

    @ConfigurationComponent(file = "missing.yml")
    static class MissingFileConfiguration {
        @ConfigurationValue(path = "value", defaultValue = "fallback")
        String value;
    }

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;
    private ConfigurationLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());

        write("config.yml", "name: main\n");
        write("taxes.yml", "enabled: true\ngovernment-account: DCGovernment\n");
        write("nested.yml", "database:\n  host: db.example.com\n");

        when(plugin.getConfig()).thenAnswer(invocation -> currentConfig());
        // saveResource is a no-op here: every file under test is already on disk, and
        // MissingFileConfiguration deliberately has neither a disk file nor a packaged one.
        loader = new ConfigurationLoader(plugin);
    }

    private FileConfiguration currentConfig() {
        return YamlConfiguration.loadConfiguration(dataFolder.resolve("config.yml").toFile());
    }

    private void write(String name, String contents) throws IOException {
        Files.writeString(dataFolder.resolve(name), contents, StandardCharsets.UTF_8);
    }

    @Test
    void aPluginWithNoPackagedConfigYmlStillConstructs() {
        // Bukkit throws from saveDefaultConfig() when the jar packages no config.yml. That is a
        // legitimate layout once components name their own files, so it must not abort startup.
        JavaPlugin bare = mock(JavaPlugin.class);
        when(bare.getLogger()).thenReturn(mock(Logger.class));
        when(bare.getDataFolder()).thenReturn(dataFolder.toFile());
        doThrow(new IllegalArgumentException("The embedded resource 'config.yml' cannot be found"))
                .when(bare).saveDefaultConfig();

        assertDoesNotThrow(() -> new ConfigurationLoader(bare));
    }

    @Test
    void componentReadsItsOwnNamedFile() {
        loader.scanPackage(MultiFileConfigurationTest.class.getPackageName());

        TaxConfiguration taxes = loader.getComponent(TaxConfiguration.class);
        assertTrue(taxes.enabled);
        assertEquals("DCGovernment", taxes.account);
    }

    @Test
    void componentWithoutAFileStillReadsConfigYml() {
        loader.scanPackage(MultiFileConfigurationTest.class.getPackageName());

        assertEquals("main", loader.getComponent(MainConfiguration.class).name);
    }

    @Test
    void componentRootPathScopesItsFields() {
        loader.scanPackage(MultiFileConfigurationTest.class.getPackageName());

        assertEquals("db.example.com", loader.getComponent(RootedConfiguration.class).host);
    }

    @Test
    void absentFileFallsBackToDeclaredDefaults() {
        loader.scanPackage(MultiFileConfigurationTest.class.getPackageName());

        assertEquals("fallback", loader.getComponent(MissingFileConfiguration.class).value);
    }

    @Test
    void getFileExposesAnAuxiliaryFileDirectly() {
        FileConfiguration taxes = loader.getFile("taxes.yml");

        assertNotNull(taxes);
        assertEquals("DCGovernment", taxes.getString("government-account"));
    }

    @Test
    void getFileForConfigYmlReturnsThePluginConfig() {
        assertEquals("main", loader.getFile("config.yml").getString("name"));
    }

    @Test
    void reloadRereadsAuxiliaryFilesFromDisk() throws IOException {
        loader.scanPackage(MultiFileConfigurationTest.class.getPackageName());
        assertEquals("DCGovernment", loader.getComponent(TaxConfiguration.class).account);

        write("taxes.yml", "enabled: false\ngovernment-account: NewTreasury\n");
        loader.reload();

        TaxConfiguration reloaded = loader.getComponent(TaxConfiguration.class);
        assertEquals("NewTreasury", reloaded.account);
        assertEquals(false, reloaded.enabled);
    }

    @Test
    void reloadSwapsInAFreshInstanceRatherThanMutatingTheOldOne() throws IOException {
        loader.scanPackage(MultiFileConfigurationTest.class.getPackageName());
        TaxConfiguration before = loader.getComponent(TaxConfiguration.class);

        write("taxes.yml", "enabled: false\ngovernment-account: NewTreasury\n");
        loader.reload();

        // The atomic-reload contract: a reference captured earlier keeps its own consistent
        // snapshot instead of being mutated underneath its reader.
        assertEquals("DCGovernment", before.account);
        assertEquals("NewTreasury", loader.getComponent(TaxConfiguration.class).account);
    }
}
