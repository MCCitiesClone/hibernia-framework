package io.paradaux.hibernia.framework.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultsReconcilerTest {

    @Test
    void skipsWhenOnDiskFileMissing(@TempDir Path dir) {
        DefaultsReconciler.reconcileFile(dir.toFile(), "messages.properties",
                "a=1\n", DefaultsReconciler.Kind.PROPERTIES);
        assertFalse(Files.exists(dir.resolve("messages.properties")),
                "first install: must not create the file (framework writes it fresh)");
    }

    @Test
    void skipsWhenJarDefaultNull(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages.properties");
        Files.writeString(file, "a=1\n");
        DefaultsReconciler.reconcileFile(dir.toFile(), "messages.properties",
                null, DefaultsReconciler.Kind.PROPERTIES);
        assertEquals("a=1\n", Files.readString(file));
    }

    @Test
    void mergesAndWritesWhenChanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages.properties");
        Files.writeString(file, "a=1\n");
        DefaultsReconciler.reconcileFile(dir.toFile(), "messages.properties",
                "a=1\nb=2\n", DefaultsReconciler.Kind.PROPERTIES);
        String result = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(result.contains("a=1"));
        assertTrue(result.contains("b=2"));
    }

    @Test
    void doesNotRewriteWhenUnchanged(@TempDir Path dir) throws Exception {
        File file = dir.resolve("messages.properties").toFile();
        Files.writeString(file.toPath(), "a=1\nb=2\n");
        long before = file.lastModified();
        DefaultsReconciler.reconcileFile(dir.toFile(), "messages.properties",
                "a=1\nb=2\n", DefaultsReconciler.Kind.PROPERTIES);
        assertEquals("a=1\nb=2\n", Files.readString(file.toPath()));
        assertEquals(before, file.lastModified(), "unchanged content must not be rewritten");
    }

    @Test
    void mergesYamlConfig(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 1\n");
        DefaultsReconciler.reconcileFile(dir.toFile(), "config.yml",
                "a: 1\nb: 2\n", DefaultsReconciler.Kind.YAML);
        String result = Files.readString(file);
        assertTrue(result.contains("a:"));
        assertTrue(result.contains("b:"));
    }
}
