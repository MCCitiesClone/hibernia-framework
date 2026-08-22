package io.paradaux.hibernia.framework.commander;

import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Reflection-driven coverage for {@link CommandManager}'s resolver lookup and optional-default
 * resolution: the {@code @OptionalArg(SENDER)} sentinel, default-value conversion, primitive↔wrapper
 * mapping and nearest-supertype resolver selection.
 */
class CommandManagerResolverTest {

    private CommandManager manager;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        manager = new CommandManager(plugin, Set.of(), Set.<ParameterResolver<?>>of());
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = CommandManager.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(manager, args);
        } catch (InvocationTargetException ite) {
            if (ite.getTargetException() instanceof Exception e) throw e;
            throw ite;
        }
    }

    private Object resolveDefault(Param param, CommandSender sender) throws Exception {
        return invoke("resolveDefault", new Class[]{Param.class, CommandSender.class}, param, sender);
    }

    private Object primitiveWrapper(Class<?> type) throws Exception {
        return invoke("primitiveWrapper", new Class[]{Class.class}, type);
    }

    private Object resolverFor(Class<?> type) throws Exception {
        return invoke("resolverFor", new Class[]{Class.class}, type);
    }

    @Test
    void resolveDefault_senderSentinel_returnsSenderWhenAssignable() throws Exception {
        CommandSender sender = mock(CommandSender.class);
        Param p = Param.optional(CommandSender.class, "target", OptionalArg.SENDER, true);
        assertSame(sender, resolveDefault(p, sender));
    }

    @Test
    void resolveDefault_senderSentinel_throwsWhenSenderNotAssignable() {
        CommandSender sender = mock(CommandSender.class);
        // Sender can't default to a String → descriptive failure.
        Param p = Param.optional(String.class, "name", OptionalArg.SENDER, true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> resolveDefault(p, sender));
        assertTrue(ex.getMessage().contains("can only default to the sender"));
    }

    @Test
    void resolveDefault_stringDefault_returnedVerbatim() throws Exception {
        Param p = Param.optional(String.class, "name", "hello", true);
        assertEquals("hello", resolveDefault(p, mock(CommandSender.class)));
    }

    @Test
    void resolveDefault_emptyDefaultForReferenceType_yieldsNull() throws Exception {
        // Non-String reference type with an empty default → null (Strings keep "").
        Param p = Param.optional(OfflinePlayer.class, "who", "", true);
        assertNull(resolveDefault(p, mock(CommandSender.class)));
    }

    @Test
    void resolveDefault_resolverRejectsInvalidDefault() {
        Param p = Param.optional(Integer.class, "n", "not-a-number", true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolveDefault(p, mock(CommandSender.class)));
        assertTrue(ex.getMessage().contains("Invalid default"));
    }

    @Test
    void resolveDefault_resolverParsesValidDefault() throws Exception {
        Param p = Param.optional(Integer.class, "n", "42", true);
        assertEquals(42, resolveDefault(p, mock(CommandSender.class)));
    }

    @Test
    void primitiveWrapper_mapsEveryPrimitive() throws Exception {
        assertEquals(Boolean.class, primitiveWrapper(boolean.class));
        assertEquals(Integer.class, primitiveWrapper(int.class));
        assertEquals(Long.class, primitiveWrapper(long.class));
        assertEquals(Double.class, primitiveWrapper(double.class));
        assertEquals(Float.class, primitiveWrapper(float.class));
        assertEquals(Short.class, primitiveWrapper(short.class));
        assertEquals(Byte.class, primitiveWrapper(byte.class));
        assertEquals(Character.class, primitiveWrapper(char.class));
        assertNull(primitiveWrapper(String.class));
    }

    @Test
    void resolverFor_usesNearestSupertypeResolver() throws Exception {
        // No resolver is registered for Player, but OfflinePlayerResolver services its supertype.
        Object resolver = resolverFor(Player.class);
        org.junit.jupiter.api.Assertions.assertNotNull(resolver);
        assertEquals(OfflinePlayer.class,
                ((ParameterResolver<?>) resolver).type());
    }

    @Test
    void routeIndex_isEmptyBeforeRegistration() {
        assertTrue(manager.routeIndex().isEmpty());
    }
}
