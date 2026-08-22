package io.paradaux.hibernia.framework.commander.resolvers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongResolverTest {

    private final LongResolver resolver = new LongResolver();

    @Test
    void type_isLong() {
        assertEquals(Long.class, resolver.type());
    }

    @Test
    void resolve_validLong() {
        assertEquals(9_000_000_000L, resolver.resolve("9000000000", null).orElseThrow());
        assertEquals(-5L, resolver.resolve("-5", null).orElseThrow());
    }

    @Test
    void resolve_invalid_isEmpty() {
        assertTrue(resolver.resolve("not-a-number", null).isEmpty());
        assertTrue(resolver.resolve("1.5", null).isEmpty());
    }

    @Test
    void suggestions_defaultEmpty() {
        assertTrue(resolver.suggestions("1", null).isEmpty());
    }
}
