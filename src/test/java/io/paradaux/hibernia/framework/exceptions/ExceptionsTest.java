package io.paradaux.hibernia.framework.exceptions;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionsTest {

    // ── BadCommandException ───────────────────────────────────────────────────

    @Test
    void badCommandException_storesMessage() {
        BadCommandException ex = new BadCommandException("bad command");
        assertEquals("bad command", ex.getMessage());
    }

    @Test
    void badCommandException_isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new BadCommandException("x"));
    }

    // ── ConflictException ─────────────────────────────────────────────────────

    @Test
    void conflictException_storesMessage() {
        ConflictException ex = new ConflictException("conflict");
        assertEquals("conflict", ex.getMessage());
    }

    @Test
    void conflictException_storesMessageAndCause() {
        Throwable cause = new IllegalStateException("root");
        ConflictException ex = new ConflictException("conflict", cause);
        assertEquals("conflict", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void conflictException_isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new ConflictException("x"));
    }

    // ── ExceedsLimitException ────────────────────────────────────────────────

    @Test
    void exceedsLimitException_storesMessage() {
        ExceedsLimitException ex = new ExceedsLimitException("limit exceeded");
        assertEquals("limit exceeded", ex.getMessage());
    }

    @Test
    void exceedsLimitException_isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new ExceedsLimitException("x"));
    }

    // ── InternalException ────────────────────────────────────────────────────

    @Test
    void internalException_storesMessage() {
        InternalException ex = new InternalException("internal error");
        assertEquals("internal error", ex.getMessage());
    }

    @Test
    void internalException_isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new InternalException("x"));
    }

    // ── NoPermissionException ────────────────────────────────────────────────

    @Test
    void noPermissionException_storesMessage() {
        NoPermissionException ex = new NoPermissionException("no permission");
        assertEquals("no permission", ex.getMessage());
    }

    @Test
    void noPermissionException_isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new NoPermissionException("x"));
    }

    // ── NotFoundException ────────────────────────────────────────────────────

    @Test
    void notFoundException_storesMessage() {
        NotFoundException ex = new NotFoundException("not found");
        assertEquals("not found", ex.getMessage());
    }

    @Test
    void notFoundException_isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new NotFoundException("x"));
    }

    // ── Throwable behaviour ──────────────────────────────────────────────────

    @Test
    void exceptionsCanBeCaughtAsRuntimeException() {
        assertThrows(RuntimeException.class, () -> { throw new BadCommandException("test"); });
        assertThrows(RuntimeException.class, () -> { throw new ConflictException("test"); });
        assertThrows(RuntimeException.class, () -> { throw new ExceedsLimitException("test"); });
        assertThrows(RuntimeException.class, () -> { throw new InternalException("test"); });
        assertThrows(RuntimeException.class, () -> { throw new NoPermissionException("test"); });
        assertThrows(RuntimeException.class, () -> { throw new NotFoundException("test"); });
    }

    // ── KeyedException contract (PAR-16) ─────────────────────────────────────

    @Test
    void semanticExceptions_areKeyedAndExposeTheirMessageAsKey() {
        assertInstanceOf(KeyedException.class, new NotFoundException("k"));
        assertInstanceOf(KeyedException.class, new BadCommandException("k"));
        assertInstanceOf(KeyedException.class, new ConflictException("k"));
        assertInstanceOf(KeyedException.class, new ExceedsLimitException("k"));
        assertInstanceOf(KeyedException.class, new NoPermissionException("k"));

        KeyedException ex = new NotFoundException("myplugin.not-found");
        assertEquals("myplugin.not-found", ex.messageKey());
        assertTrue(ex.placeholders().isEmpty());
    }

    @Test
    void keyedException_carriesPlaceholderPairs() {
        NotFoundException ex = new NotFoundException("k", "name", "Widget", "count", 3);
        assertEquals(Map.of("name", "Widget", "count", 3), ex.placeholders());
        assertEquals("k", ex.messageKey());
    }

    @Test
    void conflictException_withCause_hasEmptyPlaceholders() {
        Throwable cause = new IllegalStateException("root");
        ConflictException ex = new ConflictException("k", cause);
        assertSame(cause, ex.getCause());
        assertTrue(ex.placeholders().isEmpty());
        assertEquals("k", ex.messageKey());
    }

    @Test
    void pairs_rejectsOddCount() {
        assertThrows(IllegalArgumentException.class, () -> KeyedException.pairs("a"));
    }

    @Test
    void pairs_rejectsNonStringKey() {
        assertThrows(IllegalArgumentException.class, () -> KeyedException.pairs(1, "x"));
    }

    @Test
    void pairs_emptyIsEmptyMap() {
        assertTrue(KeyedException.pairs().isEmpty());
    }
}
