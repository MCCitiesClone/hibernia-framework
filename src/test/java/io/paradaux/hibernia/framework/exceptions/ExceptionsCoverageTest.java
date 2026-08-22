package io.paradaux.hibernia.framework.exceptions;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the per-class keyed varargs constructors and the {@code messageKey()} /
 * {@code placeholders()} overrides that the broad {@link ExceptionsTest} only drives
 * through {@code NotFoundException}.
 */
class ExceptionsCoverageTest {

    @Test
    void badCommand_varargsConstructor_carriesKeyAndPlaceholders() {
        BadCommandException ex = new BadCommandException("plugin.bad", "field", "amount", "n", 3);
        assertEquals("plugin.bad", ex.messageKey());
        assertEquals(Map.of("field", "amount", "n", 3), ex.placeholders());
    }

    @Test
    void noPermission_varargsConstructor_carriesKeyAndPlaceholders() {
        NoPermissionException ex = new NoPermissionException("plugin.denied", "node", "eco.use");
        assertEquals("plugin.denied", ex.messageKey());
        assertEquals(Map.of("node", "eco.use"), ex.placeholders());
    }

    @Test
    void exceedsLimit_varargsConstructor_carriesKeyAndPlaceholders() {
        ExceedsLimitException ex = new ExceedsLimitException("plugin.too-much", "max", 64);
        assertEquals("plugin.too-much", ex.messageKey());
        assertEquals(Map.of("max", 64), ex.placeholders());
    }

    @Test
    void conflict_varargsConstructor_carriesKeyAndPlaceholders() {
        ConflictException ex = new ConflictException("plugin.clash", "name", "Widget");
        assertEquals("plugin.clash", ex.messageKey());
        assertEquals(Map.of("name", "Widget"), ex.placeholders());
    }

    @Test
    void conflict_causeConstructor_hasEmptyPlaceholders() {
        Throwable cause = new IllegalStateException("root");
        ConflictException ex = new ConflictException("k", cause);
        assertSame(cause, ex.getCause());
        assertTrue(ex.placeholders().isEmpty());
    }

    @Test
    void badCommand_singleArgConstructor_messageKeyEqualsMessage() {
        BadCommandException ex = new BadCommandException("only.key");
        assertEquals("only.key", ex.messageKey());
        assertTrue(ex.placeholders().isEmpty());
    }

    @Test
    void noPermission_singleArgConstructor_messageKeyEqualsMessage() {
        NoPermissionException ex = new NoPermissionException("only.key");
        assertEquals("only.key", ex.messageKey());
        assertTrue(ex.placeholders().isEmpty());
    }

    @Test
    void exceedsLimit_singleArgConstructor_messageKeyEqualsMessage() {
        ExceedsLimitException ex = new ExceedsLimitException("only.key");
        assertEquals("only.key", ex.messageKey());
        assertTrue(ex.placeholders().isEmpty());
    }
}
