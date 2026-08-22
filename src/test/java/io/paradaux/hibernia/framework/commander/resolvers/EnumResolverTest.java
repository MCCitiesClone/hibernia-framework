package io.paradaux.hibernia.framework.commander.resolvers;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EnumResolverTest {

    enum Occupancy { IGNORE, OCCUPIED, UNOCCUPIED }

    private final EnumResolver<Occupancy> resolver = new EnumResolver<>(Occupancy.class);
    private final CommandSender sender = mock(CommandSender.class);

    @Test
    void type_isTheEnum() {
        assertEquals(Occupancy.class, resolver.type());
    }

    @Test
    void resolve_matchesConstantName() {
        assertEquals(Optional.of(Occupancy.OCCUPIED), resolver.resolve("OCCUPIED", sender));
    }

    @Test
    void resolve_isCaseInsensitive() {
        // Clients complete the lower-case suggestion, so the resolver must accept it back.
        assertEquals(Optional.of(Occupancy.UNOCCUPIED), resolver.resolve("unoccupied", sender));
        assertEquals(Optional.of(Occupancy.IGNORE), resolver.resolve("Ignore", sender));
    }

    @Test
    void resolve_unknownConstantIsEmpty() {
        assertTrue(resolver.resolve("nope", sender).isEmpty());
        assertTrue(resolver.resolve("", sender).isEmpty());
    }

    @Test
    void suggestions_listConstants() {
        List<String> suggestions = resolver.suggestions("", sender);

        assertEquals(List.of("ignore", "occupied", "unoccupied"), suggestions);
    }

    @Test
    void suggestions_filterByPrefix() {
        assertEquals(List.of("occupied"), resolver.suggestions("occ", sender));
        assertTrue(resolver.suggestions("zzz", sender).isEmpty());
    }

    @Test
    void suggestions_prefixMatchIsCaseInsensitive() {
        assertFalse(resolver.suggestions("OCC", sender).isEmpty());
    }
}
