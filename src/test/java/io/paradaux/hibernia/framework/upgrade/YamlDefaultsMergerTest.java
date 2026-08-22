package io.paradaux.hibernia.framework.upgrade;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlDefaultsMergerTest {

    private static YamlConfiguration parse(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.options().parseComments(true);
        c.loadFromString(yaml);
        return c;
    }

    @Test
    void addsMissingTopLevelKey() throws Exception {
        String onDisk = "greeting: Hello\n";
        String jar = "greeting: Hi\nfarewell: Bye\n";
        String merged = YamlDefaultsMerger.merge(onDisk, jar);
        YamlConfiguration c = parse(merged);
        assertEquals("Hello", c.getString("greeting"), "operator value preserved");
        assertEquals("Bye", c.getString("farewell"), "missing key added");
    }

    @Test
    void doesNotOverwritePopulatedOperatorValue() throws Exception {
        String onDisk = "max-players: 200\n";
        String jar = "max-players: 20\n";
        String merged = YamlDefaultsMerger.merge(onDisk, jar);
        assertSame(onDisk, merged, "no new keys → unchanged");
        assertEquals(200, parse(merged).getInt("max-players"));
    }

    @Test
    void addsMissingNestedSubtree() throws Exception {
        String onDisk = "economy:\n  starting-balance: 500\n";
        String jar = "economy:\n  starting-balance: 100\n  tax:\n    rate: 0.05\n    enabled: true\n";
        String merged = YamlDefaultsMerger.merge(onDisk, jar);
        YamlConfiguration c = parse(merged);
        assertEquals(500, c.getInt("economy.starting-balance"), "operator value preserved");
        assertEquals("0.05", c.getString("economy.tax.rate"));
        assertTrue(c.getBoolean("economy.tax.enabled"));
    }

    @Test
    void carriesCommentForNewKey() throws Exception {
        String onDisk = "a: 1\n";
        String jar = "a: 1\n# describes the new option\nb: 2\n";
        String merged = YamlDefaultsMerger.merge(onDisk, jar);
        assertTrue(merged.contains("describes the new option"), merged);
        assertEquals(2, parse(merged).getInt("b"));
    }

    @Test
    void idempotentWhenNoNewKeys() throws Exception {
        String onDisk = "a: 1\nb: 2\n";
        String jar = "a: 9\nb: 9\n";
        String merged = YamlDefaultsMerger.merge(onDisk, jar);
        assertSame(onDisk, merged);
        // Running again on already-merged content is also a no-op.
        String full = "a: 1\n";
        String once = YamlDefaultsMerger.merge(full, "a: 1\nc: 3\n");
        assertEquals(once, YamlDefaultsMerger.merge(once, "a: 1\nc: 3\n"));
    }

    @Test
    void preservesOperatorAddedKey() throws Exception {
        String onDisk = "a: 1\ncustom: keep-me\n";
        String jar = "a: 1\nnew: added\n";
        YamlConfiguration c = parse(YamlDefaultsMerger.merge(onDisk, jar));
        assertEquals("keep-me", c.getString("custom"));
        assertEquals("added", c.getString("new"));
    }
}
