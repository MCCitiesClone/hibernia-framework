package io.paradaux.hibernia.framework.commander.resolvers;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DoubleResolverTest {

    private final DoubleResolver resolver = new DoubleResolver();
    private final CommandSender sender = mock(CommandSender.class);

    @Test
    void type_isDouble() {
        assertEquals(Double.class, resolver.type());
    }

    @Test
    void resolve_parsesAPlainNumber() {
        assertEquals(Optional.of(12.5d), resolver.resolve("12.5", sender));
    }

    @Test
    void resolve_acceptsThousandsSeparators() {
        // Prices are typed by hand, and a comma in one should not be a parse failure.
        assertEquals(Optional.of(1234567.89d), resolver.resolve("1,234,567.89", sender));
    }

    @Test
    void resolve_rejectsNonNumbers() {
        assertTrue(resolver.resolve("free", sender).isEmpty());
        assertTrue(resolver.resolve("", sender).isEmpty());
        assertTrue(resolver.resolve(null, sender).isEmpty());
    }

    @Test
    void suggestions_areEmpty() {
        // Brigadier supplies native number suggestions for the node itself.
        assertTrue(resolver.suggestions("", sender).isEmpty());
    }
}
