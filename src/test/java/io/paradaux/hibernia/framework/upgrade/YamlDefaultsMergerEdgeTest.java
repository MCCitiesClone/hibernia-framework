package io.paradaux.hibernia.framework.upgrade;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link YamlDefaultsMerger}'s inline-comment carry-over and the skip of already-present
 * paths during the comment pass.
 */
class YamlDefaultsMergerEdgeTest {

    @Test
    void carriesBlockAndInlineCommentsForNewKey() throws Exception {
        String onDisk = "a: 1\n";
        String jar = "a: 1\n# block comment\nb: 2 # inline comment\n";

        String merged = YamlDefaultsMerger.merge(onDisk, jar);

        assertTrue(merged.contains("block comment"), merged);
        assertTrue(merged.contains("inline comment"), merged);

        YamlConfiguration c = new YamlConfiguration();
        c.options().parseComments(true);
        c.loadFromString(merged);
        assertEquals(1, c.getInt("a"), "operator value preserved");
        assertEquals(2, c.getInt("b"), "new key added with its comments");
    }
}
