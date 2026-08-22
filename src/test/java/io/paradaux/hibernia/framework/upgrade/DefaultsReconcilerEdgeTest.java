package io.paradaux.hibernia.framework.upgrade;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Failure-path coverage for {@link DefaultsReconciler}: malformed inputs and unwritable state
 * locations must be swallowed (logged) rather than crash plugin boot.
 */
class DefaultsReconcilerEdgeTest {

    @Test
    void reconcileFile_malformedYaml_isCaughtNotPropagated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: [unclosed\n");   // invalid YAML on disk

        // Must not throw despite the merge blowing up on the malformed document.
        DefaultsReconciler.reconcileFile(dir.toFile(), "config.yml", "a: 1\nb: 2\n",
                DefaultsReconciler.Kind.YAML);

        // The unparseable operator file is left untouched.
        assertEquals("a: [unclosed\n", Files.readString(file));
    }

    @Test
    void reconcile_versionLookupFailure_runsWithoutRecordingMarker(@TempDir Path dir) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getPluginMeta()).thenThrow(new RuntimeException("no meta on this host"));
        lenient().when(plugin.getResource(anyString())).thenReturn(null);

        DefaultsReconciler.reconcile(plugin);

        // Version unknown → the marker is never written.
        assertFalse(Files.exists(dir.resolve(".hibernia-version")));
    }

    @Test
    void reconcile_unwritableMarkerDirectory_isSwallowed(@TempDir Path dir) throws Exception {
        // .hibernia-version already exists as a *directory* → writeString fails.
        Files.createDirectory(dir.resolve(".hibernia-version"));

        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("1.0.0");
        when(plugin.getPluginMeta()).thenReturn(meta);
        lenient().when(plugin.getResource(anyString())).thenReturn(null);

        // Must not throw even though the marker can't be written.
        DefaultsReconciler.reconcile(plugin);
    }

    @Test
    void reconcile_dataFolderIsAFile_cannotCreateMarker(@TempDir Path dir) throws Exception {
        File asFile = dir.resolve("data").toFile();
        Files.writeString(asFile.toPath(), "not a folder");

        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(asFile);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("2.0.0");
        when(plugin.getPluginMeta()).thenReturn(meta);
        lenient().when(plugin.getResource(anyString())).thenReturn(null);

        DefaultsReconciler.reconcile(plugin);

        // The data "folder" stayed a file; no marker directory was created under it.
        assertEquals("not a folder", Files.readString(asFile.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void reconcile_resourceReadFailure_treatedAsNoBundledDefault(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("messages.properties"), "a=1\n");

        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("3.0.0");
        when(plugin.getPluginMeta()).thenReturn(meta);

        // A resource stream that blows up on read → resource() must swallow it and return null.
        InputStream throwing = mock(InputStream.class);
        when(throwing.readAllBytes()).thenThrow(new IOException("disk gone"));
        when(plugin.getResource("messages.properties")).thenReturn(throwing);
        when(plugin.getResource("config.yml")).thenReturn(null);

        DefaultsReconciler.reconcile(plugin);

        // No usable jar default → the operator file is left untouched.
        assertEquals("a=1\n", Files.readString(dir.resolve("messages.properties")));
    }
}
