package io.paradaux.hibernia.framework.commander.resolvers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BooleanResolverTest {

    private final BooleanResolver resolver = new BooleanResolver();

    @Test
    void type_isBoolean() {
        assertEquals(Boolean.class, resolver.type());
    }

    @Test
    void resolve_truthyTokens() {
        for (String t : List.of("true", "YES", "y", "1", "on")) {
            assertTrue(resolver.resolve(t, null).orElseThrow(), t);
        }
    }

    @Test
    void resolve_falsyTokens() {
        for (String t : List.of("false", "NO", "n", "0", "off")) {
            assertFalse(resolver.resolve(t, null).orElseThrow(), t);
        }
    }

    @Test
    void resolve_unknownOrNull_isEmpty() {
        assertTrue(resolver.resolve("maybe", null).isEmpty());
        assertTrue(resolver.resolve(null, null).isEmpty());
    }

    @Test
    void suggestions_emptyPrefixReturnsBoth() {
        assertEquals(List.of("true", "false"), resolver.suggestions("", null));
        assertEquals(List.of("true", "false"), resolver.suggestions(null, null));
    }

    @Test
    void suggestions_filterByPrefix() {
        assertEquals(List.of("true"), resolver.suggestions("t", null));
        assertEquals(List.of("false"), resolver.suggestions("FA", null));
        assertTrue(resolver.suggestions("z", null).isEmpty());
    }
}
