package io.paradaux.hibernia.framework.upgrade;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesDefaultsMergerTest {

    private static Properties parse(String content) throws Exception {
        Properties p = new Properties();
        p.load(new StringReader(content));
        return p;
    }

    @Test
    void addsMissingKeysFromJarDefault() throws Exception {
        String onDisk = "greeting=Hello\n";
        String jar = "greeting=Hi there\nfarewell=Goodbye\n";

        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        Properties p = parse(merged);

        assertEquals("Hello", p.getProperty("greeting"), "operator value must win");
        assertEquals("Goodbye", p.getProperty("farewell"), "missing key filled from jar");
    }

    @Test
    void leavesExistingOperatorValueUntouched() throws Exception {
        String onDisk = "greeting=Custom operator text\n";
        String jar = "greeting=Default text\n";
        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        // No new keys → unchanged input returned verbatim.
        assertSame(onDisk, merged);
        assertEquals("Custom operator text", parse(merged).getProperty("greeting"));
    }

    @Test
    void preservesOperatorAddedKeys() throws Exception {
        String onDisk = "greeting=Hi\nmy.custom=value\n";
        String jar = "greeting=Hi\nnew.key=fresh\n";
        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        Properties p = parse(merged);
        assertEquals("value", p.getProperty("my.custom"));
        assertEquals("fresh", p.getProperty("new.key"));
    }

    @Test
    void idempotentWhenNoNewKeys() {
        String onDisk = "a=1\nb=2\n";
        String jar = "a=x\nb=y\n";
        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        assertSame(onDisk, merged);
        // Second run is a no-op too.
        assertEquals(merged, PropertiesDefaultsMerger.merge(merged, jar));
    }

    @Test
    void carriesCommentBlockAboveNewKey() {
        String onDisk = "a=1\n";
        String jar = "a=1\n# explains b\n# second comment line\nb=2\n";
        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        assertTrue(merged.contains("# explains b"), merged);
        assertTrue(merged.contains("# second comment line"), merged);
        assertTrue(merged.contains("b=2"), merged);
    }

    @Test
    void noDuplicateForKeyDefinedWithDifferentSeparator() throws Exception {
        // On-disk uses ':' separator; jar uses '='. Same key — must not be re-added.
        String onDisk = "greeting:Hello\n";
        String jar = "greeting=Hi\n";
        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        assertSame(onDisk, merged);
        assertEquals(1, parse(merged).size());
    }

    @Test
    void handlesContinuationLinesInExistingFile() {
        String onDisk = "multi=line one \\\n  line two\n";
        String jar = "multi=default\nextra=added\n";
        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        // 'multi' already exists (despite continuation) so it isn't re-added; 'extra' is.
        assertFalse(merged.contains("multi=default"), merged);
        assertTrue(merged.contains("extra=added"), merged);
    }

    @Test
    void appendsMultiLineDefaultValue() throws Exception {
        String onDisk = "a=1\n";
        String jar = "a=1\nbig=first \\\n  second\n";
        String merged = PropertiesDefaultsMerger.merge(onDisk, jar);
        assertEquals("first second", parse(merged).getProperty("big"));
    }
}
