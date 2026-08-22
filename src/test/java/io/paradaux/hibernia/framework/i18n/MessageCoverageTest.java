package io.paradaux.hibernia.framework.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fills the residual branches of {@link Message}: the {@code has}/{@code component}/{@code componentOr}
 * overloads, namespaced palette resolution, the bundled-default bootstrap, and the malformed-input
 * fallbacks not exercised by {@link MessageTest}.
 */
class MessageCoverageTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
    }

    @Test
    void setPapiSupport_appliesBridge_andRejectsNull() throws Exception {
        writeMessages("line=%token% end");
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        // Package-private Guice injection point.
        message.setPapiSupport((player, text) -> text.replace("%token%", "Hello"));

        assertEquals("Hello end", PLAIN.serialize(message.component("line")));
        assertThrows(NullPointerException.class, () -> message.setPapiSupport(null));
    }

    @Test
    void has_byLocaleAndBySender() throws Exception {
        writeMessages("present=value");
        stubSaveResourceNoop();

        Message message = new Message(plugin);

        assertTrue(message.has(Locale.ENGLISH, "present"));
        assertFalse(message.has(Locale.ENGLISH, "absent"));

        CommandSender console = mock(CommandSender.class);
        assertTrue(message.has(console, "present"));
        assertFalse(message.has(console, "absent"));
    }

    @Test
    void component_localeVarargsOverload() throws Exception {
        writeMessages("greet=Hi {name}");
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        Component out = message.component(Locale.ENGLISH, "greet", "name", "Sam");
        assertEquals("Hi Sam", PLAIN.serialize(out));
    }

    @Test
    void componentOr_keyFallbackOverload_andSenderOverload() throws Exception {
        writeMessages("present=<green>{m}");
        stubSaveResourceNoop();

        Message message = new Message(plugin);

        // (key, fallback, Map) overload — key absent → fallback used.
        Component fallback = message.componentOr("absent", "<red>{m}</red>", Map.of("m", "boom"));
        assertEquals("boom", PLAIN.serialize(fallback));

        // (sender, key, fallback, Map) overload — key present → bundle value used.
        CommandSender sender = mock(CommandSender.class);
        Component fromKey = message.componentOr(sender, "present", "<red>{m}</red>", Map.of("m", "yay"));
        assertEquals("yay", PLAIN.serialize(fromKey));
    }

    @Test
    void constructor_savesBundledDefaultWhenNoBaseFileExists() {
        // No messages.properties written → ensureDefaultFile calls saveResource.
        stubSaveResourceNoop();

        new Message(plugin);

        verify(plugin).saveResource("messages.properties", false);
    }

    @Test
    void constructor_throwsWhenDataFolderCannotBeCreated() throws Exception {
        // Point the data folder at a path whose parent is a regular file → mkdirs fails.
        File blocker = tempDir.resolve("blocker").toFile();
        Files.writeString(blocker.toPath(), "x");
        when(plugin.getDataFolder()).thenReturn(new File(blocker, "nested"));

        assertThrows(IllegalStateException.class, () -> new Message(plugin));
    }

    @Test
    void reload_skipsBundleThatFailsToParse() throws Exception {
        writeMessages("k=ok");
        // Invalid unicode escape makes Properties.load throw — must be caught, not propagated.
        writeBundle("ga", "bad=\\uZZZZ");
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        // Base bundle still loads fine.
        assertEquals("ok", message.format("k"));
    }

    @Test
    void defaultLocaleWithCountry_extendsTheFallbackChain() throws Exception {
        writeMessages("k=base");
        writeBundle("pt", "k=portugues");
        stubSaveResourceNoop();

        Message message = new Message(plugin).defaultLocale(Locale.of("pt", "BR"));

        // English request → chain walks en → pt_BR (default) → pt (its language) → base.
        assertEquals("portugues", message.format(Locale.ENGLISH, "k"));
    }

    @Test
    void format_richValuePassesMarkupThroughInStringPath() throws Exception {
        writeMessages("k=Hello {name}");
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        String out = message.format("k", Map.of("name", Message.rich("<b>X</b>")));
        assertEquals("Hello <b>X</b>", out);
    }

    @Test
    void format_unknownPlaceholderStaysLiteral() throws Exception {
        writeMessages("k=Hi {ghost}");
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        assertEquals("Hi {ghost}", message.format("k"));
    }

    @Test
    void component_unknownPlaceholderStaysLiteral() throws Exception {
        writeMessages("k=Hi {ghost}");
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        assertEquals("Hi {ghost}", PLAIN.serialize(message.component("k")));
    }

    @Test
    void component_namespacedPaletteResolves() throws Exception {
        writeMessages("""
                cmd.placeholder.tag=<red>RED</red>
                cmd.line={tag} hi
                """);
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        assertEquals("RED hi", PLAIN.serialize(message.component("cmd.line")));
    }

    @Test
    void component_argumentlessTagRendersLiterally() throws Exception {
        // A <click> tag with no arguments isn't a valid event; MiniMessage renders it as literal text
        // rather than throwing, so no markup is silently dropped.
        writeMessages("k=<click>broken</click>");
        stubSaveResourceNoop();

        Message message = new Message(plugin);
        assertEquals("<click>broken</click>", PLAIN.serialize(message.component("k")));
    }

    @Test
    void rich_toStringReturnsValue() {
        assertEquals("<b>x</b>", Message.rich("<b>x</b>").toString());
    }

    private void stubSaveResourceNoop() {
        doAnswer(invocation -> null).when(plugin).saveResource(anyString(), anyBoolean());
    }

    private void writeMessages(String content) throws IOException {
        Files.writeString(tempDir.resolve("messages.properties"), content, StandardCharsets.UTF_8);
    }

    private void writeBundle(String localeSuffix, String content) throws IOException {
        Files.writeString(tempDir.resolve("messages_" + localeSuffix + ".properties"), content, StandardCharsets.UTF_8);
    }
}
