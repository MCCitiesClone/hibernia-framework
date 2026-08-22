package io.paradaux.hibernia.framework.upgrade;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultsReconcilerPluginTest {

    private JavaPlugin pluginWith(Path dataFolder, String version) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        PluginMeta meta = mock(PluginMeta.class);
        lenient().when(meta.getVersion()).thenReturn(version);
        lenient().when(plugin.getPluginMeta()).thenReturn(meta);
        lenient().when(plugin.getResource(anyString())).thenReturn(null);
        return plugin;
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void firstInstall_recordsVersion_andDoesNotCreateFiles(@TempDir Path dir) throws Exception {
        JavaPlugin plugin = pluginWith(dir, "1.0.0");
        when(plugin.getResource("messages.properties")).thenReturn(stream("a=1\n"));

        DefaultsReconciler.reconcile(plugin);

        assertEquals("1.0.0", Files.readString(dir.resolve(".hibernia-version")).strip());
        assertFalse(Files.exists(dir.resolve("messages.properties")),
                "framework writes the default itself on first install");
    }

    @Test
    void sameVersion_isNoOp(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".hibernia-version"), "1.0.0");
        Files.writeString(dir.resolve("messages.properties"), "a=1\n");
        JavaPlugin plugin = pluginWith(dir, "1.0.0");
        // If reconcile ran it would merge b; it must not, because the version is unchanged.
        lenient().when(plugin.getResource("messages.properties")).thenReturn(stream("a=1\nb=2\n"));

        DefaultsReconciler.reconcile(plugin);

        assertEquals("a=1\n", Files.readString(dir.resolve("messages.properties")));
    }

    @Test
    void upgrade_mergesMissingKeys_andUpdatesMarker(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".hibernia-version"), "0.9.0");
        Files.writeString(dir.resolve("messages.properties"), "a=1\n");
        JavaPlugin plugin = pluginWith(dir, "1.0.0");
        when(plugin.getResource("messages.properties")).thenReturn(stream("a=1\nb=2\n"));

        DefaultsReconciler.reconcile(plugin);

        String merged = Files.readString(dir.resolve("messages.properties"));
        assertTrue(merged.contains("a=1"));
        assertTrue(merged.contains("b=2"), merged);
        assertEquals("1.0.0", Files.readString(dir.resolve(".hibernia-version")).strip());
    }
}
