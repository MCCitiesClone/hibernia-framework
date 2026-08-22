package io.paradaux.hibernia.framework.upgrade;

import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reconciles an operator's on-disk {@code config.yml} and {@code messages.properties}
 * against the freshly shipped jar defaults when a plugin is upgraded (PAR-251).
 *
 * <p>HiberniaFramework deliberately never overwrites operator files, so keys a new
 * release adds would otherwise never reach an operator who already had the file.
 * This additively fills in the missing keys — adding what the jar declares but the
 * disk lacks, and never touching existing operator values or operator-added keys
 * (see {@link PropertiesDefaultsMerger} / {@link YamlDefaultsMerger}).</p>
 *
 * <p><strong>Ordering.</strong> {@link #reconcile(JavaPlugin)} must run in the
 * framework bootstrap <em>before</em> {@code Message} and {@code ConfigurationLoader}
 * read their files, so they load the freshly merged content. {@code HiberniaModule}
 * invokes it first thing in its constructor.</p>
 *
 * <p><strong>Version gate.</strong> The reconcile is additive and idempotent, so it
 * could safely run every boot; it is gated on a change of the plugin version (recorded
 * in {@code .hibernia-version} in the data folder) purely to avoid needless rewrites
 * and to give a clean "an upgrade happened" signal. A key the operator deliberately
 * deleted will reappear on the next upgrade — that matches the "fill in missing
 * defaults" intent.</p>
 */
@Slf4j
public final class DefaultsReconciler {

    static final String STATE_FILE = ".hibernia-version";

    private DefaultsReconciler() {
    }

    /** Run the version-gated reconcile for {@code plugin}. Never throws — failures are logged. */
    public static void reconcile(JavaPlugin plugin) {
        File dataFolder = plugin.getDataFolder();
        if (dataFolder == null) {
            return;
        }
        String current = versionOf(plugin);
        String recorded = readRecordedVersion(dataFolder);
        if (current != null && current.equals(recorded)) {
            return; // already reconciled for this version
        }

        try {
            reconcileFile(dataFolder, "messages.properties", resource(plugin, "messages.properties"), Kind.PROPERTIES);
            reconcileFile(dataFolder, "config.yml", resource(plugin, "config.yml"), Kind.YAML);
        } catch (Exception e) {
            log.warn("Defaults reconciliation failed: {}", e.getMessage());
        }

        writeRecordedVersion(dataFolder, current);
    }

    enum Kind { PROPERTIES, YAML }

    /**
     * Merge {@code jarDefault} into the on-disk {@code fileName} under {@code dataFolder},
     * writing back only when content changed. No-ops when the operator has no file yet
     * (first install — the framework writes the default fresh) or the jar bundles no
     * such resource.
     */
    static void reconcileFile(File dataFolder, String fileName, String jarDefault, Kind kind) {
        if (jarDefault == null) {
            return; // plugin ships no bundled default for this file
        }
        File onDisk = new File(dataFolder, fileName);
        if (!onDisk.isFile()) {
            return; // first install: nothing to reconcile yet
        }
        try {
            String current = Files.readString(onDisk.toPath(), StandardCharsets.UTF_8);
            String merged = switch (kind) {
                case PROPERTIES -> PropertiesDefaultsMerger.merge(current, jarDefault);
                case YAML -> YamlDefaultsMerger.merge(current, jarDefault);
            };
            if (!merged.equals(current)) {
                Files.writeString(onDisk.toPath(), merged, StandardCharsets.UTF_8);
                log.info("Reconciled new default keys into {}", fileName);
            }
        } catch (Exception e) {
            log.warn("Could not reconcile {}: {}", fileName, e.getMessage());
        }
    }

    private static String versionOf(JavaPlugin plugin) {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (Throwable t) {
            return null; // unusual hosts / test doubles — fall back to running every boot
        }
    }

    private static String resource(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readRecordedVersion(File dataFolder) {
        Path state = new File(dataFolder, STATE_FILE).toPath();
        if (!Files.isRegularFile(state)) {
            return null;
        }
        try {
            return Files.readString(state, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeRecordedVersion(File dataFolder, String version) {
        if (version == null) {
            return;
        }
        try {
            if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
                return;
            }
            Files.writeString(new File(dataFolder, STATE_FILE).toPath(), version, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not record plugin version marker: {}", e.getMessage());
        }
    }
}
