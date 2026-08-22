package io.paradaux.hibernia.framework.commander;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds paginated, permission-filtered help from the routes the
 * {@link CommandManager} actually registered, so a plugin's {@code /x help} stays
 * in sync with its real commands instead of being hand-maintained.
 *
 * <p>Source of truth is {@link CommandManager#routeIndex()} — every
 * {@link RouteInfo} carries the route pattern, its {@code @Description} and the
 * effective permission. Routes whose permission the sender lacks are hidden, so
 * help shows each viewer only what they can run.</p>
 *
 * <p>The instance methods read the live route index; the {@code static} overloads
 * take an explicit {@code List<RouteInfo>} and are pure (no server needed), which
 * is what the unit tests exercise. Typical wiring in a consumer:</p>
 * <pre>
 * {@literal @}Route("help [page]")
 * public void help(@Sender CommandSender sender, @OptionalArg(value = "page", defaultValue = "1") int page) {
 *     sender.sendMessage(helpGenerator.render(sender, "treasury", page));
 * }
 * </pre>
 */
@Singleton
public class HelpGenerator {

    /** Default routes shown per page when no size is given. */
    public static final int DEFAULT_PAGE_SIZE = 8;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CommandManager commandManager;

    @Inject
    public HelpGenerator(CommandManager commandManager) {
        this.commandManager = Objects.requireNonNull(commandManager, "commandManager");
    }

    /** Render page {@code page} of {@code root}'s help for {@code sender} at the default page size. */
    public Component render(CommandSender sender, String root, int page) {
        return render(commandManager.routeIndex(), sender, root, page, DEFAULT_PAGE_SIZE);
    }

    public Component render(CommandSender sender, String root, int page, int pageSize) {
        return render(commandManager.routeIndex(), sender, root, page, pageSize);
    }

    /** The routes under {@code root} that {@code sender} may run, ordered for display. */
    public List<RouteInfo> visibleRoutes(CommandSender sender, String root) {
        return visible(commandManager.routeIndex(), sender, root);
    }

    /** Number of pages of visible help for {@code sender} under {@code root} (at least 1). */
    public int pageCount(CommandSender sender, String root, int pageSize) {
        return pageCount(visible(commandManager.routeIndex(), sender, root).size(), pageSize);
    }

    // ── pure helpers (server-independent) ───────────────────────────────────────────

    /**
     * Filter {@code routes} to those under {@code root} (case-insensitive) that
     * {@code sender} is permitted to run — a route with no permission is visible to
     * everyone — ordered by pattern for stable output.
     */
    public static List<RouteInfo> visible(List<RouteInfo> routes, CommandSender sender, String root) {
        String wanted = root.toLowerCase(Locale.ROOT);
        return routes.stream()
                .filter(r -> r.root().toLowerCase(Locale.ROOT).equals(wanted))
                .filter(r -> r.permission() == null || sender.hasPermission(r.permission()))
                .sorted(Comparator.comparing(RouteInfo::pattern))
                .toList();
    }

    public static int pageCount(int visibleCount, int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be >= 1");
        if (visibleCount <= 0) return 1;
        return (visibleCount + pageSize - 1) / pageSize;
    }

    /**
     * Render one page of help. {@code page} is 1-based and clamped into range; an
     * empty result still renders the header (with "no commands"). Route patterns and
     * descriptions are escaped, so a description can never inject MiniMessage markup.
     */
    public static Component render(List<RouteInfo> routes, CommandSender sender, String root, int page, int pageSize) {
        List<RouteInfo> shown = visible(routes, sender, root);
        int pages = pageCount(shown.size(), pageSize);
        int clamped = Math.max(1, Math.min(page, pages));

        Component out = MINI.deserialize("<gold><bold>/" + MINI.escapeTags(root) + "</bold></gold> "
                + "<gray>help (page " + clamped + "/" + pages + ")</gray>");

        if (shown.isEmpty()) {
            return out.appendNewline().append(MINI.deserialize("<gray>No commands available.</gray>"));
        }

        int from = (clamped - 1) * pageSize;
        int to = Math.min(from + pageSize, shown.size());
        for (RouteInfo r : shown.subList(from, to)) {
            out = out.appendNewline().append(renderLine(root, r));
        }
        return out;
    }

    private static Component renderLine(String root, RouteInfo r) {
        String label = r.pattern().isEmpty()
                ? "/" + root
                : "/" + root + " " + r.pattern();
        StringBuilder line = new StringBuilder("<yellow>").append(MINI.escapeTags(label)).append("</yellow>");
        if (!r.description().isEmpty()) {
            line.append("<gray> — ").append(MINI.escapeTags(r.description())).append("</gray>");
        }
        return MINI.deserialize(line.toString());
    }
}
