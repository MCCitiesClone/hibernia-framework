package io.paradaux.hibernia.framework.commander;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelpGeneratorTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static RouteInfo route(String root, String pattern, String description, String permission) {
        return new RouteInfo(root, pattern, description, permission, false);
    }

    private static CommandSender allowAll() {
        CommandSender s = mock(CommandSender.class);
        lenient().when(s.hasPermission(anyString())).thenReturn(true);
        return s;
    }

    private static CommandSender denyAll() {
        CommandSender s = mock(CommandSender.class);
        lenient().when(s.hasPermission(anyString())).thenReturn(false);
        return s;
    }

    private final List<RouteInfo> routes = List.of(
            route("eco", "give <player> <amount>", "Give money", "eco.give"),
            route("eco", "balance", "Check balance", null),
            route("eco", "", "Economy root", null),
            route("shop", "open", "Open the shop", null)   // different root — must be excluded
    );

    @Test
    void visible_filtersByRootAndPermission() {
        List<RouteInfo> v = HelpGenerator.visible(routes, allowAll(), "eco");
        assertEquals(3, v.size());
        assertTrue(v.stream().allMatch(r -> r.root().equals("eco")));
    }

    @Test
    void visible_isCaseInsensitiveOnRoot() {
        assertEquals(3, HelpGenerator.visible(routes, allowAll(), "ECO").size());
    }

    @Test
    void visible_hidesRoutesTheSenderCannotRun() {
        List<RouteInfo> v = HelpGenerator.visible(routes, denyAll(), "eco");
        // The permissioned "give" route is hidden; the two null-permission routes remain.
        assertEquals(2, v.size());
        assertTrue(v.stream().noneMatch(r -> "eco.give".equals(r.permission())));
    }

    @Test
    void visible_sortsByPattern() {
        List<RouteInfo> v = HelpGenerator.visible(routes, allowAll(), "eco");
        assertEquals("", v.get(0).pattern());
        assertEquals("balance", v.get(1).pattern());
        assertEquals("give <player> <amount>", v.get(2).pattern());
    }

    @Test
    void pageCount_roundsUpAndIsAtLeastOne() {
        assertEquals(1, HelpGenerator.pageCount(0, 8));
        assertEquals(1, HelpGenerator.pageCount(8, 8));
        assertEquals(2, HelpGenerator.pageCount(9, 8));
        assertEquals(3, HelpGenerator.pageCount(5, 2));
    }

    @Test
    void pageCount_rejectsNonPositivePageSize() {
        assertThrows(IllegalArgumentException.class, () -> HelpGenerator.pageCount(5, 0));
    }

    @Test
    void render_showsHeaderAndLinesForPage() {
        Component c = HelpGenerator.render(routes, allowAll(), "eco", 1, 2);
        String text = PLAIN.serialize(c);
        assertTrue(text.contains("/eco"), text);
        assertTrue(text.contains("help (page 1/2)"), text);   // 3 visible @ size 2 -> 2 pages
        assertTrue(text.contains("balance"), text);
        assertTrue(text.contains("Check balance"), text);
    }

    @Test
    void render_clampsPageIntoRange() {
        Component high = HelpGenerator.render(routes, allowAll(), "eco", 99, 2);
        assertTrue(PLAIN.serialize(high).contains("page 2/2"));
        Component low = HelpGenerator.render(routes, allowAll(), "eco", -3, 2);
        assertTrue(PLAIN.serialize(low).contains("page 1/2"));
    }

    @Test
    void render_paginatesContent() {
        // Page 1 (size 2) holds the first two by pattern order: "" (root) and "balance".
        String page1 = PLAIN.serialize(HelpGenerator.render(routes, allowAll(), "eco", 1, 2));
        assertTrue(page1.contains("Economy root"));
        assertTrue(page1.contains("Check balance"));
        assertFalse(page1.contains("Give money"));
        // Page 2 holds the remaining "give" route.
        String page2 = PLAIN.serialize(HelpGenerator.render(routes, allowAll(), "eco", 2, 2));
        assertTrue(page2.contains("Give money"));
        assertFalse(page2.contains("Check balance"));
    }

    @Test
    void render_handlesNoVisibleCommands() {
        String text = PLAIN.serialize(HelpGenerator.render(routes, denyAll(), "missing", 1, 8));
        assertTrue(text.contains("No commands available."), text);
    }

    @Test
    void render_escapesDescriptionMarkup() {
        List<RouteInfo> evil = List.of(route("eco", "x", "<red>not a tag</red>", null));
        String text = PLAIN.serialize(HelpGenerator.render(evil, allowAll(), "eco", 1, 8));
        // The literal tag text survives (escaped), proving it was not parsed as colour markup.
        assertTrue(text.contains("<red>not a tag</red>"), text);
    }

    @Test
    void instanceRender_readsLiveRouteIndex() {
        CommandManager cm = mock(CommandManager.class);
        when(cm.routeIndex()).thenReturn(routes);
        HelpGenerator gen = new HelpGenerator(cm);
        assertEquals(3, gen.visibleRoutes(allowAll(), "eco").size());
        assertEquals(2, gen.pageCount(allowAll(), "eco", 2));
        assertTrue(PLAIN.serialize(gen.render(allowAll(), "eco", 1)).contains("/eco"));
        // Explicit page-size overload reads the same live index.
        assertTrue(PLAIN.serialize(gen.render(allowAll(), "eco", 1, 2)).contains("help (page 1/2)"));
    }
}
