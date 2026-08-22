package io.paradaux.hibernia.framework.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StringUtilsTest {

    // ── random32 ─────────────────────────────────────────────────────────────

    @Test
    void random32_returnsExactly32Characters() {
        assertEquals(32, StringUtils.random32().length());
    }

    @Test
    void random32_containsOnlyAlphanumericChars() {
        String result = StringUtils.random32();
        assertTrue(result.matches("[0-9A-Za-z]{32}"),
                "Expected only alphanumeric chars but got: " + result);
    }

    @Test
    void random32_multipleCallsProduceDifferentValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seen.add(StringUtils.random32());
        }
        // With 62^32 possibilities the chance of a collision is negligible
        assertTrue(seen.size() > 1, "All 10 random32() calls returned the same value");
    }

    // ── sanitize ─────────────────────────────────────────────────────────────

    @Test
    void sanitize_null_returnsEmpty() {
        assertEquals("", StringUtils.sanitize(null));
    }

    @Test
    void sanitize_emptyString_returnsEmpty() {
        assertEquals("", StringUtils.sanitize(""));
    }

    @Test
    void sanitize_blankString_returnsEmpty() {
        assertEquals("", StringUtils.sanitize("   "));
    }

    @Test
    void sanitize_cleanAlphanumericInput_unchanged() {
        assertEquals("hello world", StringUtils.sanitize("hello world"));
    }

    @Test
    void sanitize_underscoresPreserved() {
        assertEquals("hello_world", StringUtils.sanitize("hello_world"));
    }

    @Test
    void sanitize_miniMessageTagsStripped() {
        assertEquals("bold text", StringUtils.sanitize("<bold>bold text</bold>"));
    }

    @Test
    void sanitize_nestedMiniMessageTags_allStripped() {
        assertEquals("text", StringUtils.sanitize("<red><bold>text</bold></red>"));
    }

    @Test
    void sanitize_specialCharactersStripped() {
        assertEquals("hello world", StringUtils.sanitize("hello! world@#$%"));
    }

    @Test
    void sanitize_multipleSpacesCollapsed() {
        assertEquals("a b", StringUtils.sanitize("a   b"));
    }

    @Test
    void sanitize_leadingAndTrailingSpacesTrimmed() {
        assertEquals("hello", StringUtils.sanitize("  hello  "));
    }

    @Test
    void sanitize_pureSpecialChars_returnsEmpty() {
        assertEquals("", StringUtils.sanitize("!@#$%^&*()"));
    }

    @Test
    void sanitize_mixedUnicode_lettersPreserved() {
        // U+00E9 = é (LATIN SMALL LETTER E WITH ACUTE) — alphabetic, should survive
        String result = StringUtils.sanitize("caf\u00e9");
        assertEquals("caf\u00e9", result);
    }

    @Test
    void sanitize_digitsPreserved() {
        assertEquals("test123", StringUtils.sanitize("test123"));
    }

    @Test
    void sanitize_hyphenAndPeriodPreserved() {
        assertEquals("starting-balances", StringUtils.sanitize("starting-balances"));
        assertEquals("v1.2.3", StringUtils.sanitize("v1.2.3"));
        assertEquals("foo-bar.baz_qux", StringUtils.sanitize("foo-bar.baz_qux"));
    }

    @Test
    void sanitize_miniMessageColorTag_removedButTextPreserved() {
        assertEquals("Hello", StringUtils.sanitize("<color:#FF0000>Hello</color>"));
    }

    // ── startsWithNumber ─────────────────────────────────────────────────────

    @Test
    void startsWithNumber_null_returnsFalse() {
        assertFalse(StringUtils.startsWithNumber(null));
    }

    @Test
    void startsWithNumber_emptyString_returnsFalse() {
        assertFalse(StringUtils.startsWithNumber(""));
    }

    @Test
    void startsWithNumber_startsWithDigit_returnsTrue() {
        assertTrue(StringUtils.startsWithNumber("1abc"));
    }

    @Test
    void startsWithNumber_startsWithZero_returnsTrue() {
        assertTrue(StringUtils.startsWithNumber("0test"));
    }

    @Test
    void startsWithNumber_allDigits_returnsTrue() {
        assertTrue(StringUtils.startsWithNumber("12345"));
    }

    @Test
    void startsWithNumber_startsWithLetter_returnsFalse() {
        assertFalse(StringUtils.startsWithNumber("abc123"));
    }

    @Test
    void startsWithNumber_startsWithUnderscore_returnsFalse() {
        assertFalse(StringUtils.startsWithNumber("_1ab"));
    }

    @Test
    void startsWithNumber_singleDigit_returnsTrue() {
        assertTrue(StringUtils.startsWithNumber("9"));
    }

    @Test
    void constructor_isInstantiable() {
        // The class is a static utility holder; the default constructor exists but is unused.
        assertNotNull(new StringUtils());
    }
}
