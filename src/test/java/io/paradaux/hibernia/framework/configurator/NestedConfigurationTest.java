package io.paradaux.hibernia.framework.configurator;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nested object, list-of-object and map binding, exercised against real parsed YAML rather
 * than a mocked configuration — the shapes under test (a list whose entries are mappings, an
 * enum-keyed map of objects) are exactly where a mock would stop resembling Bukkit.
 */
class NestedConfigurationTest {

    enum State { FOR_SALE, SOLD }

    @ConfigurationObject
    static class SignTemplate {
        @ConfigurationValue(path = "lines")
        List<String> lines;

        @ConfigurationValue(path = "right-click-commands")
        List<String> rightClickCommands;
    }

    @ConfigurationObject
    static class Profile {
        @ConfigurationValue(path = "priority", defaultValue = "0")
        int priority;

        @ConfigurationValue(path = "flags")
        Map<String, String> flags;

        @ConfigurationValue(path = "sign")
        SignTemplate sign;
    }

    @ConfigurationObject
    static class Permission {
        @ConfigurationValue(path = "node")
        String node;

        @ConfigurationValue(path = "default", defaultValue = "OP")
        String defaultValue;
    }

    @ConfigurationObject
    static class Tag {
        @ConfigurationValue(path = "tag-id")
        String id;

        @ConfigurationValue(path = "tag-display-name")
        Component displayName;

        @ConfigurationValue(path = "permission")
        Permission permission;
    }

    static class Root {
        @ConfigurationValue(path = "global")
        Map<State, Profile> global;

        @ConfigurationValue(path = "tags")
        List<Tag> tags;
    }

    enum PermissionDefault { OP, TRUE, FALSE }

    static class EnumRoot {
        @ConfigurationValue(path = "a")
        PermissionDefault a;

        @ConfigurationValue(path = "b")
        PermissionDefault b;

        @ConfigurationValue(path = "c")
        PermissionDefault c;
    }

    static class IntKeyRoot {
        @ConfigurationValue(path = "levels")
        Map<Integer, String> levels;
    }

    @ConfigurationObject
    static class NoCtor {
        @ConfigurationValue(path = "value")
        String value;

        NoCtor(String required) {
            this.value = required;
        }
    }

    static class NoCtorRoot {
        @ConfigurationValue(path = "bad")
        NoCtor bad;
    }

    @ConfigurationObject
    static class SelfReferential {
        @ConfigurationValue(path = "child")
        SelfReferential child;
    }

    static class CycleRoot {
        @ConfigurationValue(path = "node")
        SelfReferential node;
    }

    private static final String YAML = """
            global:
              FOR_SALE:
                priority: 5
                flags:
                  pvp: deny
                  greeting: "This region is for sale!"
                sign:
                  lines:
                    - "<blue>[For Sale]"
                    - "<region>"
                  right-click-commands:
                    - "realty buy <region>"
              SOLD:
                priority: 10
                flags:
                  pvp: "deny -g NON_MEMBERS"
            tags:
              - tag-id: residential
                tag-display-name: "<green>Residential"
                permission:
                  node: realty.tag.residential
                  default: OP
              - tag-id: commercial
                tag-display-name: "<gold>Commercial"
                permission:
                  node: realty.tag.commercial
                  default: TRUE
            """;

    private ConfigurationProcessor processor;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        processor = new ConfigurationProcessor(plugin);
        config = YamlConfiguration.loadConfiguration(new StringReader(YAML));
    }

    @Test
    void bindsEnumKeyedMapOfObjects() {
        Root root = new Root();
        processor.process(root, config);

        assertNotNull(root.global);
        assertEquals(2, root.global.size());
        assertTrue(root.global.containsKey(State.FOR_SALE));
        assertTrue(root.global.containsKey(State.SOLD));
        assertEquals(5, root.global.get(State.FOR_SALE).priority);
        assertEquals(10, root.global.get(State.SOLD).priority);
    }

    @Test
    void bindsStringMapInsideNestedObject() {
        Root root = new Root();
        processor.process(root, config);

        Map<String, String> flags = root.global.get(State.FOR_SALE).flags;
        assertEquals("deny", flags.get("pvp"));
        assertEquals("This region is for sale!", flags.get("greeting"));
    }

    @Test
    void bindsObjectNestedTwoLevelsDeep() {
        Root root = new Root();
        processor.process(root, config);

        SignTemplate sign = root.global.get(State.FOR_SALE).sign;
        assertNotNull(sign);
        assertEquals(List.of("<blue>[For Sale]", "<region>"), sign.lines);
        assertEquals(List.of("realty buy <region>"), sign.rightClickCommands);
    }

    @Test
    void absentOptionalObjectBindsToNull() {
        Root root = new Root();
        processor.process(root, config);

        // SOLD declares no sign section; that is "not configured", not a failure.
        assertNull(root.global.get(State.SOLD).sign);
    }

    @Test
    void bindsListOfObjects() {
        Root root = new Root();
        processor.process(root, config);

        assertEquals(2, root.tags.size());
        assertEquals("residential", root.tags.get(0).id);
        assertEquals("commercial", root.tags.get(1).id);
    }

    @Test
    void bindsObjectNestedInsideAListEntry() {
        Root root = new Root();
        processor.process(root, config);

        assertEquals("realty.tag.residential", root.tags.get(0).permission.node);
        assertEquals("OP", root.tags.get(0).permission.defaultValue);
        // Unquoted TRUE is a YAML boolean, so it reaches a String field as "true".
        assertEquals("true", root.tags.get(1).permission.defaultValue);
    }

    @Test
    void bindsComponentFromMiniMessage() {
        Root root = new Root();
        processor.process(root, config);

        Component displayName = root.tags.get(0).displayName;
        assertNotNull(displayName);
        assertEquals("Residential", PlainTextComponentSerializer.plainText().serialize(displayName));
    }

    @Test
    void listBindingLeavesNoScratchKeysBehind() {
        Root root = new Root();
        processor.process(root, config);

        // The binder wraps each list entry in a temporary section; none may survive into a save.
        assertTrue(config.getKeys(true).stream().noneMatch(key -> key.contains("hibernia$tmp$")),
                "temporary wrapper sections leaked into the configuration");
    }

    @Test
    void absentListYieldsEmptyRatherThanNull() {
        YamlConfiguration empty = YamlConfiguration.loadConfiguration(new StringReader("other: 1\n"));
        Root root = new Root();
        processor.process(root, empty);

        assertEquals(List.of(), root.tags);
        assertEquals(Map.of(), root.global);
    }

    @Test
    void enumConstantsMatchRegardlessOfCase() {
        // `b: TRUE` is a YAML boolean and reaches us as "true"; `c: op` is simply lower case.
        // Both must still bind, or an ordinary permission-default config would be rejected.
        YamlConfiguration enums = YamlConfiguration.loadConfiguration(new StringReader("""
                a: OP
                b: TRUE
                c: op
                """));
        EnumRoot root = new EnumRoot();

        processor.process(root, enums);

        assertEquals(PermissionDefault.OP, root.a);
        assertEquals(PermissionDefault.TRUE, root.b);
        assertEquals(PermissionDefault.OP, root.c);
    }

    @Test
    void unknownEnumConstantLeavesTheFieldUnset() {
        YamlConfiguration enums = YamlConfiguration.loadConfiguration(new StringReader("a: NOPE\n"));
        EnumRoot root = new EnumRoot();

        processor.process(root, enums);

        assertNull(root.a);
    }

    @Test
    void integerKeyedMapBindsAndSkipsNonNumericKeys() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                levels:
                  1: one
                  2: two
                  notanumber: three
                """));
        IntKeyRoot root = new IntKeyRoot();

        processor.process(root, yaml);

        assertEquals("one", root.levels.get(1));
        assertEquals("two", root.levels.get(2));
        assertEquals(2, root.levels.size());
    }

    @Test
    void invalidEnumMapKeyIsSkippedRatherThanFailingTheWholeMap() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                global:
                  FOR_SALE:
                    priority: 1
                  NOT_A_STATE:
                    priority: 2
                """));
        Root root = new Root();

        processor.process(root, yaml);

        assertEquals(1, root.global.size());
        assertTrue(root.global.containsKey(State.FOR_SALE));
    }

    @Test
    void nonMappingListEntryIsSkipped() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                tags:
                  - tag-id: fine
                  - just-a-string
                """));
        Root root = new Root();

        processor.process(root, yaml);

        assertEquals(1, root.tags.size());
        assertEquals("fine", root.tags.get(0).id);
    }

    @Test
    void objectWithoutANoArgConstructorIsReported() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader("bad:\n  value: x\n"));
        NoCtorRoot root = new NoCtorRoot();

        processor.process(root, yaml);

        assertNull(root.bad);
    }

    @Test
    void aTypeMayNestInsideItselfAtMultipleDepths() {
        // Recursion is bounded by depth, not by type: a tree of like-typed nodes is ordinary
        // configuration and must bind, not be rejected as a cycle.
        YamlConfiguration nested = YamlConfiguration.loadConfiguration(new StringReader("""
                node:
                  child:
                    child:
                      child: {}
                """));
        CycleRoot root = new CycleRoot();

        processor.process(root, nested);

        assertNotNull(root.node);
        assertNotNull(root.node.child);
        assertNotNull(root.node.child.child);
        // `child: {}` is still a mapping, so it binds; the absence shows one level deeper.
        assertNotNull(root.node.child.child.child);
        assertNull(root.node.child.child.child.child);
    }
}
