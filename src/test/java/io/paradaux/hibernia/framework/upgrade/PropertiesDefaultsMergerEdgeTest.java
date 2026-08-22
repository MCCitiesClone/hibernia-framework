package io.paradaux.hibernia.framework.upgrade;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link PropertiesDefaultsMerger}'s formatting edge cases: appending when the operator file
 * lacks a trailing newline, leading-whitespace lines in the jar default, and escaped key separators.
 */
class PropertiesDefaultsMergerEdgeTest {

    private static Properties parse(String content) throws Exception {
        Properties p = new Properties();
        p.load(new StringReader(content));
        return p;
    }

    @Test
    void appendsEolWhenOperatorFileHasNoTrailingNewline() throws Exception {
        String onDisk = "a=1";   // deliberately no trailing newline
        String jar = "a=1\nb=2\n";

        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);

        Properties p = parse(merged);
        assertEquals("1", p.getProperty("a"));
        assertEquals("2", p.getProperty("b"));
        assertTrue(merged.contains("a=1\n"), "a separated from the appended block by a newline");
    }

    @Test
    void skipsLeadingWhitespaceCommentLinesInJar() throws Exception {
        String onDisk = "a=1\n";
        String jar = "a=1\n\t  # indented comment\nb=2\n";

        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);

        assertTrue(merged.contains("indented comment"), merged);
        assertEquals("2", parse(merged).getProperty("b"));
    }

    @Test
    void malformedOnDiskFile_isTreatedAsHavingNoKeys() throws Exception {
        // A bad \\uXXXX escape makes Properties.load throw IllegalArgumentException (unchecked);
        // merge() must swallow it (treat as no keys) rather than crash boot.
        String onDisk = "broken=\\uZZZZ\n";
        String jar = "fresh=value\n";

        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);

        // With no recognised keys on disk, the jar default is appended.
        assertTrue(merged.contains("fresh=value"), merged);
    }

    @Test
    void appendsKeyWithEscapedSeparator() throws Exception {
        String onDisk = "a=1\n";
        // The key contains an escaped ':' — parseKey must unescape it to compare against existing keys.
        String jar = "a=1\nweird\\:key=value\n";

        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);

        assertEquals("value", parse(merged).getProperty("weird:key"));
    }
}
