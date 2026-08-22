package io.paradaux.hibernia.framework.upgrade;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Additive, comment-preserving deep merge of a bundled {@code config.yml} default
 * into an operator's on-disk copy (PAR-253).
 *
 * <p>Every key path the jar default declares but the on-disk file is missing is
 * inserted at its default value, carrying across the comment block above it (and
 * any inline comment) from the jar. Populated operator values, operator-added keys
 * and existing comments are never overwritten — only genuinely missing leaves are
 * filled in. A run with no new keys returns the input unchanged (byte-identical).</p>
 *
 * <p>Uses Bukkit's {@code parseComments} comment APIs ({@link YamlConfiguration})
 * rather than {@code copyDefaults}/{@code setDefaults}, which would strip comments
 * and reorder the file.</p>
 */
public final class YamlDefaultsMerger {

    private YamlDefaultsMerger() {
    }

    /**
     * @return the merged YAML, or {@code onDiskYaml} unchanged when the jar default
     *         introduces no new keys.
     * @throws InvalidConfigurationException if either document is not valid YAML
     */
    public static String merge(String onDiskYaml, String jarYaml) throws InvalidConfigurationException {
        YamlConfiguration disk = load(onDiskYaml);
        YamlConfiguration jar = load(jarYaml);

        Set<String> jarPaths = jar.getKeys(true);
        Set<String> originallyMissing = new LinkedHashSet<>();
        for (String path : jarPaths) {
            if (!disk.contains(path)) {
                originallyMissing.add(path);
            }
        }
        if (originallyMissing.isEmpty()) {
            return onDiskYaml;
        }

        // Pass 1 — materialise missing leaves (this also creates intermediate sections).
        for (String path : jarPaths) {
            if (originallyMissing.contains(path) && !jar.isConfigurationSection(path)) {
                disk.set(path, jar.get(path));
            }
        }

        // Pass 2 — copy comments for every originally-missing path (parent-first, so a
        // newly created section already exists before we annotate it).
        for (String path : jarPaths) {
            if (!originallyMissing.contains(path) || !disk.contains(path)) {
                continue;
            }
            List<String> comments = jar.getComments(path);
            if (!comments.isEmpty()) {
                disk.setComments(path, comments);
            }
            List<String> inline = jar.getInlineComments(path);
            if (!inline.isEmpty()) {
                disk.setInlineComments(path, inline);
            }
        }

        return disk.saveToString();
    }

    private static YamlConfiguration load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        config.loadFromString(yaml);
        return config;
    }
}
