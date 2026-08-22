package io.paradaux.hibernia.framework.commander.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BigDecimalArgumentTypeTest {

    private final BigDecimalArgumentType type = BigDecimalArgumentType.bigDecimal();

    @Test
    void factory_returnsSingleton() {
        assertSame(BigDecimalArgumentType.bigDecimal(), type);
    }

    @Test
    void parse_readsContiguousNonWhitespaceToken() throws Exception {
        // The comma-bearing token survives whole (unlike word()), and parsing stops at whitespace.
        StringReader reader = new StringReader("19,993 extra");
        assertEquals("19,993", type.parse(reader));
        assertEquals(' ', reader.peek());
    }

    @Test
    void parse_acceptsScientificAndSignedMagnitudes() throws Exception {
        assertEquals("-1.5e10", type.parse(new StringReader("-1.5e10")));
        assertEquals("+0.0001", type.parse(new StringReader("+0.0001")));
    }

    @Test
    void parse_emptyOrLeadingWhitespace_throws() {
        assertThrows(CommandSyntaxException.class, () -> type.parse(new StringReader("")));
        assertThrows(CommandSyntaxException.class, () -> type.parse(new StringReader("   ")));
    }

    @Test
    void nativeType_isWordForClientPrediction() {
        assertInstanceOf(StringArgumentType.class, type.getNativeType());
    }
}
