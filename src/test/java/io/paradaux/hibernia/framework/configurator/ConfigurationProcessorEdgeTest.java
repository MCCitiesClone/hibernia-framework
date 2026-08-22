package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the remaining {@link ConfigurationProcessor} branches: the complex-type passthrough and the
 * blank-{@code BigDecimal} short-circuit.
 */
class ConfigurationProcessorEdgeTest {

    private Plugin plugin;
    private FileConfiguration config;
    private ConfigurationProcessor processor;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        processor = new ConfigurationProcessor(plugin);
    }

    static class ComplexTarget {
        @ConfigurationValue(path = "x.thing", defaultValue = "ignored")
        Object thing;
    }

    @Test
    void complexType_fallsBackToRawConfigGet() {
        Object raw = new java.util.ArrayList<>(java.util.List.of("a", "b"));
        when(config.contains("x.thing")).thenReturn(true);
        when(config.get("x.thing")).thenReturn(raw);

        ComplexTarget target = new ComplexTarget();
        processor.process(target);

        assertSame(raw, target.thing);
    }

    static class BigDecimalTarget {
        @ConfigurationValue(path = "money.amount", defaultValue = "10")
        BigDecimal amount;
    }

    @Test
    void blankBigDecimalScalar_yieldsNull() {
        when(config.contains("money.amount")).thenReturn(true);
        when(config.get("money.amount")).thenReturn("   ");   // blank scalar

        BigDecimalTarget target = new BigDecimalTarget();
        processor.process(target);

        assertNull(target.amount);
    }
}
