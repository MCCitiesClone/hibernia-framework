package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationProcessorTest {

    private Plugin plugin;
    private FileConfiguration config;
    private ConfigurationProcessor processor;
    private Logger logger;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        config = mock(FileConfiguration.class);
        logger = mock(Logger.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        processor = new ConfigurationProcessor(plugin);
    }

    // ── String injection ─────────────────────────────────────────────────────

    static class StringTarget {
        @ConfigurationValue(path = "server.name", defaultValue = "default")
        String name;
    }

    @Test
    void process_injectsStringValue() {
        when(config.contains("server.name")).thenReturn(true);
        when(config.getString("server.name", "default")).thenReturn("MyServer");

        StringTarget target = new StringTarget();
        processor.process(target);

        assertEquals("MyServer", target.name);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_string() {
        when(config.contains("server.name")).thenReturn(false);
        when(config.getString("server.name", "default")).thenReturn("default");

        StringTarget target = new StringTarget();
        processor.process(target);

        assertEquals("default", target.name);
    }

    // ── int injection ─────────────────────────────────────────────────────────

    static class IntTarget {
        @ConfigurationValue(path = "server.maxPlayers", defaultValue = "20")
        int maxPlayers;
    }

    @Test
    void process_injectsIntValue() {
        when(config.contains("server.maxPlayers")).thenReturn(true);
        when(config.getInt("server.maxPlayers")).thenReturn(100);

        IntTarget target = new IntTarget();
        processor.process(target);

        assertEquals(100, target.maxPlayers);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_int() {
        when(config.contains("server.maxPlayers")).thenReturn(false);

        IntTarget target = new IntTarget();
        processor.process(target);

        assertEquals(20, target.maxPlayers);
    }

    // ── boolean injection ────────────────────────────────────────────────────

    static class BoolTarget {
        @ConfigurationValue(path = "server.pvp", defaultValue = "true")
        boolean pvp;
    }

    @Test
    void process_injectsBooleanValue() {
        when(config.contains("server.pvp")).thenReturn(true);
        when(config.getBoolean("server.pvp")).thenReturn(false);

        BoolTarget target = new BoolTarget();
        processor.process(target);

        assertFalse(target.pvp);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_boolean() {
        when(config.contains("server.pvp")).thenReturn(false);

        BoolTarget target = new BoolTarget();
        processor.process(target);

        assertTrue(target.pvp);
    }

    // ── double injection ─────────────────────────────────────────────────────

    static class DoubleTarget {
        @ConfigurationValue(path = "economy.tax", defaultValue = "0.15")
        double tax;
    }

    @Test
    void process_injectsDoubleValue() {
        when(config.contains("economy.tax")).thenReturn(true);
        when(config.getDouble("economy.tax")).thenReturn(0.25);

        DoubleTarget target = new DoubleTarget();
        processor.process(target);

        assertEquals(0.25, target.tax, 1e-9);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_double() {
        when(config.contains("economy.tax")).thenReturn(false);

        DoubleTarget target = new DoubleTarget();
        processor.process(target);

        assertEquals(0.15, target.tax, 1e-9);
    }

    // ── long injection ───────────────────────────────────────────────────────

    static class LongTarget {
        @ConfigurationValue(path = "db.timeout", defaultValue = "5000")
        long timeout;
    }

    @Test
    void process_injectsLongValue() {
        when(config.contains("db.timeout")).thenReturn(true);
        when(config.getLong("db.timeout")).thenReturn(30000L);

        LongTarget target = new LongTarget();
        processor.process(target);

        assertEquals(30000L, target.timeout);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_long() {
        when(config.contains("db.timeout")).thenReturn(false);

        LongTarget target = new LongTarget();
        processor.process(target);

        assertEquals(5000L, target.timeout);
    }

    // ── List injection ───────────────────────────────────────────────────────

    static class ListTarget {
        @ConfigurationValue(path = "worlds.list", defaultValue = "")
        List<String> worlds;
    }

    @Test
    void process_injectsStringList() {
        when(config.contains("worlds.list")).thenReturn(true);
        when(config.getStringList("worlds.list")).thenReturn(List.of("world", "nether"));

        ListTarget target = new ListTarget();
        processor.process(target);

        assertEquals(List.of("world", "nether"), target.worlds);
    }

    // ── final field skipped ──────────────────────────────────────────────────

    static class FinalTarget {
        @ConfigurationValue(path = "some.path", defaultValue = "x")
        final String value = "original";
    }

    @Test
    void process_skipsFinalFields() {
        when(config.contains("some.path")).thenReturn(true);
        when(config.getString("some.path", "x")).thenReturn("injected");

        FinalTarget target = new FinalTarget();
        processor.process(target);

        // Final field must remain unchanged
        assertEquals("original", target.value);
        verify(logger).warning(contains("final field"));
    }

    // ── path absent, no default → warning ───────────────────────────────────

    static class NoDefaultTarget {
        @ConfigurationValue(path = "missing.key", defaultValue = "")
        String key = "untouched";
    }

    @Test
    void process_warnWhenPathAbsentAndNoDefault() {
        when(config.contains("missing.key")).thenReturn(false);

        NoDefaultTarget target = new NoDefaultTarget();
        processor.process(target);

        verify(logger).warning(contains("missing.key"));
        assertEquals("untouched", target.key);
    }

    // ── Enum injection ───────────────────────────────────────────────────────

    enum Difficulty { EASY, NORMAL, HARD }

    static class EnumTarget {
        @ConfigurationValue(path = "game.difficulty", defaultValue = "NORMAL")
        Difficulty difficulty;
    }

    @Test
    void process_injectsEnumValue() {
        when(config.contains("game.difficulty")).thenReturn(true);
        when(config.getString("game.difficulty", "NORMAL")).thenReturn("HARD");

        EnumTarget target = new EnumTarget();
        processor.process(target);

        assertEquals(Difficulty.HARD, target.difficulty);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_enum() {
        when(config.contains("game.difficulty")).thenReturn(false);
        when(config.getString("game.difficulty", "NORMAL")).thenReturn("NORMAL");

        EnumTarget target = new EnumTarget();
        processor.process(target);

        assertEquals(Difficulty.NORMAL, target.difficulty);
    }

    // ── float injection ──────────────────────────────────────────────────────

    static class FloatTarget {
        @ConfigurationValue(path = "render.scale", defaultValue = "1.5")
        float scale;
    }

    @Test
    void process_injectsFloatValue() {
        when(config.contains("render.scale")).thenReturn(true);
        when(config.getDouble("render.scale")).thenReturn(2.5);

        FloatTarget target = new FloatTarget();
        processor.process(target);

        assertEquals(2.5f, target.scale, 1e-6);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_float() {
        when(config.contains("render.scale")).thenReturn(false);

        FloatTarget target = new FloatTarget();
        processor.process(target);

        assertEquals(1.5f, target.scale, 1e-6);
    }

    // ── invalid enum value logged with diagnostics, not swallowed ───────────

    @Test
    void process_invalidEnumValue_logsPathFieldAndAllowedValues() {
        when(config.contains("game.difficulty")).thenReturn(true);
        when(config.getString("game.difficulty", "NORMAL")).thenReturn("IMPOSSIBLE");

        EnumTarget target = new EnumTarget();
        processor.process(target);

        assertNull(target.difficulty);
        verify(logger).log(eq(java.util.logging.Level.WARNING),
                contains("game.difficulty"), any(Throwable.class));
    }

    // ── unannotated fields untouched ─────────────────────────────────────────

    static class MixedTarget {
        @ConfigurationValue(path = "annotated.value", defaultValue = "hello")
        String annotated;
        String unannotated = "original";
    }

    @Test
    void process_doesNotTouchUnannotatedFields() {
        when(config.contains("annotated.value")).thenReturn(true);
        when(config.getString("annotated.value", "hello")).thenReturn("injected");

        MixedTarget target = new MixedTarget();
        processor.process(target);

        assertEquals("injected", target.annotated);
        assertEquals("original", target.unannotated);
    }

    // ── BigDecimal injection (ADT-57: exact money, never via double) ──────────

    static class BigDecimalTarget {
        @ConfigurationValue(path = "economy.starting-balance", defaultValue = "100.00")
        BigDecimal startingBalance;
    }

    @Test
    void process_injectsBigDecimalFromStringScalar() {
        when(config.contains("economy.starting-balance")).thenReturn(true);
        when(config.get("economy.starting-balance")).thenReturn("19.99");

        BigDecimalTarget target = new BigDecimalTarget();
        processor.process(target);

        // Exact — proving it didn't round-trip through a double.
        assertEquals(new BigDecimal("19.99"), target.startingBalance);
    }

    @Test
    void process_injectsBigDecimalFromYamlNumber() {
        when(config.contains("economy.starting-balance")).thenReturn(true);
        when(config.get("economy.starting-balance")).thenReturn(2500);

        BigDecimalTarget target = new BigDecimalTarget();
        processor.process(target);

        assertEquals(new BigDecimal("2500"), target.startingBalance);
    }

    @Test
    void process_usesDefaultWhenPathAbsent_bigDecimal() {
        when(config.contains("economy.starting-balance")).thenReturn(false);

        BigDecimalTarget target = new BigDecimalTarget();
        processor.process(target);

        assertEquals(new BigDecimal("100.00"), target.startingBalance);
    }

    @Test
    void process_invalidBigDecimal_logsPathAndField() {
        when(config.contains("economy.starting-balance")).thenReturn(true);
        when(config.get("economy.starting-balance")).thenReturn("not-money");

        BigDecimalTarget target = new BigDecimalTarget();
        processor.process(target);

        assertNull(target.startingBalance);
        verify(logger).log(eq(java.util.logging.Level.WARNING),
                contains("economy.starting-balance"), any(Throwable.class));
    }
}
