package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lists of scalars that are not {@code String}.
 *
 * <p>These bound through {@code getStringList} and so held {@code String}s whatever the declared
 * element type said. Erasure lets a {@code List<UUID>} field accept that quietly, and the mismatch
 * only shows up somewhere far away as a lookup that never matches — a real plugin lost every
 * tax exemption this way, with no error anywhere.</p>
 */
class TypedScalarListTest {

    enum Mode { STRICT, LENIENT }

    record Lists(
            @ConfigurationValue(path = "uuids") List<UUID> uuids,
            @ConfigurationValue(path = "ports") List<Integer> ports,
            @ConfigurationValue(path = "amounts") List<BigDecimal> amounts,
            @ConfigurationValue(path = "modes") List<Mode> modes,
            @ConfigurationValue(path = "names") List<String> names
    ) {
    }

    private ConfigurationProcessor processor;
    private Logger logger;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        this.logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(this.logger);
        this.processor = new ConfigurationProcessor(plugin);
    }

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new StringReader(text));
    }

    @Test
    @DisplayName("each element is converted to the declared element type")
    void elementsAreTyped() {
        Lists bound = (Lists) this.processor.create(Lists.class, yaml("""
                uuids:
                  - "066ea0c9-8d1a-4b1e-9c1f-6f2f2f2f2f2f"
                  - "11111111-2222-3333-4444-555555555555"
                ports:
                  - 25565
                  - 25566
                amounts:
                  - "0.1"
                  - "1250.75"
                modes:
                  - strict
                names:
                  - "alpha"
                """));

        assertInstanceOf(UUID.class, bound.uuids().get(0));
        assertEquals(UUID.fromString("066ea0c9-8d1a-4b1e-9c1f-6f2f2f2f2f2f"), bound.uuids().get(0));
        assertEquals(2, bound.uuids().size());

        assertInstanceOf(Integer.class, bound.ports().get(0));
        assertEquals(25565, bound.ports().get(0));

        // Read as text, not through double, so the exact decimal survives.
        assertInstanceOf(BigDecimal.class, bound.amounts().get(0));
        assertEquals(new BigDecimal("0.1"), bound.amounts().get(0));

        assertEquals(Mode.STRICT, bound.modes().get(0));
        assertEquals(List.of("alpha"), bound.names());
    }

    @Test
    @DisplayName("a UUID list actually answers contains() for a parsed UUID")
    void listAnswersContains() {
        UUID exempt = UUID.fromString("066ea0c9-8d1a-4b1e-9c1f-6f2f2f2f2f2f");
        Lists bound = (Lists) this.processor.create(Lists.class, yaml("""
                uuids:
                  - "066ea0c9-8d1a-4b1e-9c1f-6f2f2f2f2f2f"
                """));

        // The whole point: before coercion this was false, and nothing anywhere said why.
        assertTrue(bound.uuids().contains(exempt));
    }

    @Test
    @DisplayName("one unparseable entry is dropped and warned about, the rest survive")
    void badEntriesAreDroppedNotFatal() {
        Lists bound = (Lists) this.processor.create(Lists.class, yaml("""
                uuids:
                  - "not-a-uuid"
                  - "11111111-2222-3333-4444-555555555555"
                """));

        assertEquals(List.of(UUID.fromString("11111111-2222-3333-4444-555555555555")),
                bound.uuids());
        org.mockito.Mockito.verify(this.logger)
                .warning(org.mockito.ArgumentMatchers.contains("not-a-uuid"));
    }

    @Test
    @DisplayName("an absent list binds empty rather than null")
    void absentListIsEmpty() {
        Lists bound = (Lists) this.processor.create(Lists.class, yaml("names:\n  - \"only\"\n"));

        assertTrue(bound.uuids().isEmpty());
        assertTrue(bound.ports().isEmpty());
    }
}
