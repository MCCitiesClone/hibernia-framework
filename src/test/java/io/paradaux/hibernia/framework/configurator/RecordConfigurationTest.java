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
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Binding onto {@code record} components. Modelled on the shapes a real plugin uses — immutable
 * settings records whose compact constructors normalise defaults — because the point of
 * constructing rather than field-injecting is that those constructors get to run.
 */
class RecordConfigurationTest {

    enum State { FOR_SALE, SOLD }

    @ConfigurationObject
    record Permission(
            @ConfigurationValue(path = "node") String node,
            @ConfigurationValue(path = "default") PermissionDefault permissionDefault
    ) {
        enum PermissionDefault { OP, TRUE, FALSE }
    }

    @ConfigurationObject
    record Tag(
            @ConfigurationValue(path = "tag-id") String tagId,
            @ConfigurationValue(path = "tag-display-name") Component displayName,
            @ConfigurationValue(path = "permission") Permission permission
    ) {
    }

    @ConfigurationObject
    record Profile(
            @ConfigurationValue(path = "priority") Integer priority,
            @ConfigurationValue(path = "flags") Map<String, String> flags
    ) {
    }

    /** Mirrors a real settings record: compact constructor clamps and defaults its inputs. */
    record Settings(
            @ConfigurationValue(path = "default-authority-uuid") UUID authority,
            @ConfigurationValue(path = "profile-reapply-per-tick") int reapplyPerTick,
            @ConfigurationValue(path = "subregion-tag-blacklist") List<String> blacklist,
            @ConfigurationValue(path = "wand-material") String wandMaterial
    ) {
        Settings {
            if (reapplyPerTick <= 0) {
                reapplyPerTick = 10;
            }
            if (blacklist == null) {
                blacklist = List.of();
            }
            if (wandMaterial == null || wandMaterial.isBlank()) {
                wandMaterial = "GOLDEN_AXE";
            }
        }
    }

    record TagRoot(@ConfigurationValue(path = "tags") List<Tag> tags) {
    }

    record ProfileRoot(@ConfigurationValue(path = "global") Map<State, Profile> global) {
    }

    @ConfigurationObject
    record RegionGroup(
            @ConfigurationValue(path = "regions") Set<String> regions,
            @ConfigurationValue(path = "states") Map<State, Profile> states
    ) {
    }

    record GroupRoot(@ConfigurationValue(path = "grouped") List<RegionGroup> grouped) {
    }

    private ConfigurationProcessor processor;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        processor = new ConfigurationProcessor(plugin);
    }

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new StringReader(text));
    }

    @Test
    void constructsARecordFromItsComponents() {
        Settings settings = (Settings) processor.create(Settings.class, yaml("""
                default-authority-uuid: "00000000-0000-0000-0000-000000000001"
                profile-reapply-per-tick: 25
                subregion-tag-blacklist:
                  - locked
                wand-material: DIAMOND_AXE
                """));

        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), settings.authority());
        assertEquals(25, settings.reapplyPerTick());
        assertEquals(List.of("locked"), settings.blacklist());
        assertEquals("DIAMOND_AXE", settings.wandMaterial());
    }

    @Test
    void compactConstructorNormalisesConfiguredValues() {
        // The whole reason to construct rather than field-inject: validation written in the
        // record applies to configured values exactly as it does to code-built ones.
        Settings settings = (Settings) processor.create(Settings.class, yaml("""
                profile-reapply-per-tick: -5
                wand-material: "  "
                """));

        assertEquals(10, settings.reapplyPerTick());
        assertEquals("GOLDEN_AXE", settings.wandMaterial());
        assertEquals(List.of(), settings.blacklist());
    }

    @Test
    void absentComponentsFallBackToNullAndPrimitiveZero() {
        Settings settings = (Settings) processor.create(Settings.class, yaml("unrelated: 1\n"));

        assertNull(settings.authority());
        // 0 reaches the compact constructor, which clamps it — no NPE unboxing null into an int.
        assertEquals(10, settings.reapplyPerTick());
    }

    @Test
    void bindsUuid() {
        Settings settings = (Settings) processor.create(Settings.class, yaml(
                "default-authority-uuid: \"123e4567-e89b-12d3-a456-426614174000\"\n"));

        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), settings.authority());
    }

    @Test
    void malformedUuidLeavesTheComponentNull() {
        Settings settings = (Settings) processor.create(Settings.class, yaml(
                "default-authority-uuid: \"not-a-uuid\"\n"));

        assertNull(settings.authority());
    }

    @Test
    void bindsAListOfNestedRecords() {
        TagRoot root = (TagRoot) processor.create(TagRoot.class, yaml("""
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
                """));

        assertEquals(2, root.tags().size());
        assertEquals("residential", root.tags().get(0).tagId());
        assertEquals("Residential",
                PlainTextComponentSerializer.plainText().serialize(root.tags().get(0).displayName()));
        assertEquals("realty.tag.residential", root.tags().get(0).permission().node());
        assertEquals(Permission.PermissionDefault.OP, root.tags().get(0).permission().permissionDefault());
        // Unquoted TRUE is a YAML boolean; case-insensitive matching still finds the constant.
        assertEquals(Permission.PermissionDefault.TRUE, root.tags().get(1).permission().permissionDefault());
    }

    @Test
    void bindsAnEnumKeyedMapOfNestedRecords() {
        ProfileRoot root = (ProfileRoot) processor.create(ProfileRoot.class, yaml("""
                global:
                  FOR_SALE:
                    priority: 5
                    flags:
                      pvp: deny
                  SOLD:
                    priority: 10
                    flags:
                      pvp: "deny -g NON_MEMBERS"
                """));

        assertEquals(2, root.global().size());
        assertEquals(5, root.global().get(State.FOR_SALE).priority());
        assertEquals("deny", root.global().get(State.FOR_SALE).flags().get("pvp"));
        assertEquals(10, root.global().get(State.SOLD).priority());
    }

    @Test
    void bindsASetComponent() {
        GroupRoot root = (GroupRoot) processor.create(GroupRoot.class, yaml("""
                grouped:
                  - regions:
                      - market_stall_1
                      - market_stall_2
                      - market_stall_1
                    states:
                      SOLD:
                        priority: 20
                        flags:
                          pvp: deny
                """));

        RegionGroup group = root.grouped().get(0);
        assertEquals(Set.of("market_stall_1", "market_stall_2"), group.regions());
        assertEquals(20, group.states().get(State.SOLD).priority());
    }

    @Test
    void absentNestedRecordIsNull() {
        TagRoot root = (TagRoot) processor.create(TagRoot.class, yaml("""
                tags:
                  - tag-id: bare
                    tag-display-name: "Bare"
                """));

        assertNotNull(root.tags().get(0));
        assertNull(root.tags().get(0).permission());
    }

    @Test
    void absentListComponentBindsEmptyRatherThanNull() {
        TagRoot root = (TagRoot) processor.create(TagRoot.class, yaml("other: 1\n"));

        assertTrue(root.tags().isEmpty());
    }
}
