package io.paradaux.hibernia.framework.i18n;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.models.HiberniaPlayer;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Templated, locale-aware messaging over {@code messages.properties} files with MiniMessage formatting
 * and {@code {placeholder}} substitution.
 *
 * <h2>Locale bundles</h2>
 * <p>The base file {@code messages.properties} is the default bundle. Translations live in sibling files
 * named with a locale suffix — {@code messages_<lang>.properties} or
 * {@code messages_<lang>_<COUNTRY>.properties} (the {@link java.util.ResourceBundle} convention), e.g.
 * {@code messages_ga.properties}, {@code messages_pt_BR.properties}. A message is rendered in the
 * recipient's client locale ({@link Player#locale()}); console and other non-player senders use the
 * configured {@linkplain #defaultLocale(Locale) default locale}.</p>
 *
 * <p>Lookup falls back <em>per key</em>: a key (or placeholder) missing from the player's locale bundle
 * falls back to the language-only bundle, then the default-locale bundle, then the base bundle. So a
 * translator only needs to override the keys they've actually translated; everything else uses the base
 * text. A plugin that ships only {@code messages.properties} behaves exactly as a single-locale plugin.</p>
 *
 * <h2>Placeholders</h2>
 * <p>{@code {name}} placeholders resolve in order: caller-supplied values, then
 * {@code <namespace>.placeholder.*} keys, then global {@code placeholder.*} keys, walking the locale
 * fallback chain (most specific first); palette entries are expanded recursively so placeholders can
 * reference each other. {@link #component}/{@link #componentOr}/{@link #send} build the message with
 * MiniMessage tag resolvers; {@link #format} is a string utility on the same key/placeholder pipeline.</p>
 *
 * <p>A placeholder value's type decides how it renders (in the {@link #component} path):</p>
 * <ul>
 *   <li>a {@link net.kyori.adventure.text.ComponentLike} — e.g. a formatted item or player name — renders
 *       as that component, with its styling intact;</li>
 *   <li>a {@link Rich} value is parsed as trusted MiniMessage markup;</li>
 *   <li>any other value is <strong>inert</strong>: inserted as literal text, so MiniMessage tags and braces
 *       in player-controlled strings cannot inject markup. (In the string-only {@link #format} path a
 *       {@code ComponentLike} is rendered via {@code toString()} — use {@link #component} for those.)</li>
 * </ul>
 */
@Slf4j
@Singleton
public final class Message {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_.]+)}");
    private static final Pattern BUNDLE_FILE =
            Pattern.compile("messages(?:_([a-zA-Z]{2,3})(?:_([A-Za-z]{2}))?)?\\.properties");
    private static final String BASE_FILE = "messages.properties";
    private static final String LBR = "\u0000_LBR_";
    private static final String RBR = "\u0000_RBR_";
    private static final int MAX_EXPANSION_DEPTH = 8;

    private final JavaPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private volatile Locale defaultLocale = Locale.ROOT;                 // base bundle
    private volatile Map<Locale, Bundle> bundles = Map.of(Locale.ROOT, Bundle.empty());
    private final Map<Locale, List<Bundle>> chainCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile PapiSupport papi = PapiSupport.NONE;

    @Inject
    public Message(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        ensureDefaultFile();
        reload();
    }

    /**
     * Optional Guice injection point for the PlaceholderAPI bridge — bound by {@code HiberniaModule}.
     * Absent binding leaves {@link PapiSupport#NONE} (no {@code %token%} resolution).
     */
    @Inject(optional = true)
    void setPapiSupport(PapiSupport papi) {
        this.papi = Objects.requireNonNull(papi, "papi");
    }

    /** Manually set the PlaceholderAPI bridge (e.g. in tests or hand-wired plugins). */
    public Message placeholders(PapiSupport papi) {
        this.papi = Objects.requireNonNull(papi, "papi");
        return this;
    }

    /**
     * Set the locale used for senders without a client locale (the console, command blocks) and as the
     * fallback before the base bundle. Defaults to {@link Locale#ROOT} (the base {@code messages.properties}).
     */
    public Message defaultLocale(Locale locale) {
        this.defaultLocale = Objects.requireNonNull(locale, "locale");
        chainCache.clear();
        return this;
    }

    /** The locales for which a bundle file was found (always includes {@link Locale#ROOT}, the base). */
    public Set<Locale> availableLocales() {
        return Set.copyOf(bundles.keySet());
    }

    /** Whether any bundle in {@code locale}'s fallback chain defines {@code key}. */
    public boolean has(Locale locale, String key) {
        return findRaw(locale, key) != null;
    }

    /** Whether any bundle in {@code sender}'s locale chain defines {@code key}. */
    public boolean has(CommandSender sender, String key) {
        return findRaw(localeOf(sender), key) != null;
    }

    /**
     * Mark a placeholder value as trusted MiniMessage markup: it is substituted verbatim (tags parsed,
     * braces expandable) instead of being escaped. Only use for values the operator controls — never for
     * raw player input.
     */
    public static Rich rich(String value) {
        return new Rich(Objects.requireNonNull(value));
    }

    /** Wrapper marking a placeholder value as trusted markup. See {@link #rich(String)}. */
    public record Rich(String value) {
        @Override
        public String toString() {
            return value;
        }
    }

    // ── formatting ────────────────────────────────────────────────────────────────

    public String format(String key, Object... kvPairs) {
        return format(defaultLocale, key, kvToMap(kvPairs));
    }

    public String format(String key, Map<String, ?> values) {
        return format(defaultLocale, key, values);
    }

    public String format(Locale locale, String key, Object... kvPairs) {
        return format(locale, key, kvToMap(kvPairs));
    }

    /** Resolve {@code key} for {@code locale} and expand its placeholders to a MiniMessage string. */
    public String format(Locale locale, String key, Map<String, ?> values) {
        String pattern = findRaw(locale, key);
        return formatPattern(locale, pattern != null ? pattern : key, namespaceOf(key), values);
    }

    public Component component(String key, Object... kvPairs) {
        return component(defaultLocale, key, kvToMap(kvPairs));
    }

    public Component component(String key, Map<String, ?> values) {
        return component(defaultLocale, key, values);
    }

    public Component component(Locale locale, String key, Object... kvPairs) {
        return component(locale, key, kvToMap(kvPairs));
    }

    /**
     * Build a {@link Component} for {@code key} in {@code locale}. Placeholder values are bound as
     * MiniMessage tag resolvers, so a value may be a {@link ComponentLike} (a formatted item or player
     * name) and render properly — unlike {@link #format(Locale, String, Map)}, which is a string utility
     * and would render such a value via {@code toString()}. ({@code %token%} PlaceholderAPI placeholders
     * are only resolved on the {@link #send} / {@link #componentOr(CommandSender, String, String, Map)}
     * paths, where a player context is available.)
     */
    public Component component(Locale locale, String key, Map<String, ?> values) {
        return build(locale, null, key, null, values);
    }

    /**
     * Render {@code key} when any bundle in the locale chain defines it, otherwise render the given
     * MiniMessage {@code fallbackPattern} through the same placeholder pipeline. Lets the framework give
     * every built-in message an operator-overridable, translatable key without requiring it in every
     * plugin's file.
     */
    public Component componentOr(String key, String fallbackPattern, Object... kvPairs) {
        return componentOr(defaultLocale, key, fallbackPattern, kvToMap(kvPairs));
    }

    public Component componentOr(String key, String fallbackPattern, Map<String, ?> values) {
        return componentOr(defaultLocale, key, fallbackPattern, values);
    }

    /** {@link #componentOr(Locale, String, String, Map)} using the sender's locale and PlaceholderAPI context. */
    public Component componentOr(CommandSender sender, String key, String fallbackPattern, Map<String, ?> values) {
        return build(localeOf(sender), papiPlayer(sender), key, fallbackPattern, values);
    }

    public Component componentOr(Locale locale, String key, String fallbackPattern, Map<String, ?> values) {
        return build(locale, null, key, fallbackPattern, values);
    }

    /**
     * Core render: resolve the pattern for {@code locale} (falling back to {@code fallbackPattern}, then
     * the key itself), and build the component with the placeholder resolvers and the PlaceholderAPI
     * context {@code papiPlayer} ({@code null} when there is no player).
     */
    private Component build(Locale locale, OfflinePlayer papiPlayer, String key, String fallbackPattern,
                           Map<String, ?> values) {
        String pattern = findRaw(locale, key);
        if (pattern == null) pattern = fallbackPattern != null ? fallbackPattern : key;
        return new ResolverRender(locale, papiPlayer, namespaceOf(key), values).render(pattern);
    }

    private static OfflinePlayer papiPlayer(CommandSender sender) {
        // Player extends OfflinePlayer; console/command-block senders carry no PlaceholderAPI context.
        return sender instanceof OfflinePlayer offlinePlayer ? offlinePlayer : null;
    }

    // ── sending ───────────────────────────────────────────────────────────────────

    /** Send {@code key} to a sender, rendered in their client locale (or the default for non-players). */
    public void send(CommandSender to, String key, Object... kvPairs) {
        to.sendMessage(build(localeOf(to), papiPlayer(to), key, null, kvToMap(kvPairs)));
    }

    public void send(HiberniaPlayer to, String key, Object... kvPairs) {
        send(to.getUniqueId(), key, kvPairs);
    }

    public void send(UUID to, String key, Object... kvPairs) {
        Player player = Bukkit.getPlayer(to);
        if (player != null) {
            send(player, key, kvPairs);
        }
    }

    /** Send to many recipients; each is rendered in their own locale and PlaceholderAPI context. */
    public void send(Collection<? extends CommandSender> recipients, String key, Object... kvPairs) {
        Map<String, Object> values = kvToMap(kvPairs);
        for (CommandSender s : recipients) {
            s.sendMessage(build(localeOf(s), papiPlayer(s), key, null, values));
        }
    }

    /** Broadcast to all online players (each in their own locale and PlaceholderAPI context) and the console. */
    public void broadcast(String key, Object... kvPairs) {
        Map<String, Object> values = kvToMap(kvPairs);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(build(localeOf(p), p, key, null, values));
        }
        Bukkit.getConsoleSender().sendMessage(build(defaultLocale, null, key, null, values));
    }

    private Locale localeOf(CommandSender sender) {
        if (sender instanceof Player player) {
            Locale locale = player.locale();
            if (locale != null) {
                return locale;
            }
        }
        return defaultLocale;
    }

    // ── loading ───────────────────────────────────────────────────────────────────

    private void ensureDefaultFile() {
        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + dir);
        }

        // Only write the bundled default when the operator has no base file yet — saveResource(…, true)
        // would overwrite operator-edited messages on every boot. replace=false is belt-and-suspenders.
        if (!new File(dir, BASE_FILE).exists()) {
            plugin.saveResource(BASE_FILE, false);
        }
    }

    /** Re-scan the data folder and reload every {@code messages*.properties} bundle from disk. */
    public synchronized void reload() {
        Map<Locale, Bundle> loaded = new HashMap<>();
        File dir = plugin.getDataFolder();
        File[] files = dir.listFiles((d, name) -> BUNDLE_FILE.matcher(name).matches());
        if (files != null) {
            for (File f : files) {
                Matcher m = BUNDLE_FILE.matcher(f.getName());
                if (!m.matches()) continue;
                Locale locale = localeFromMatch(m);
                Properties props = new Properties();
                try (var reader = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
                    props.load(reader);
                    loaded.put(locale, Bundle.of(props));
                } catch (Exception e) {
                    log.error("Failed to load {}: {}", f.getName(), e.getMessage());
                }
            }
        }
        // The base bundle must always exist as the final fallback, even if unreadable.
        loaded.putIfAbsent(Locale.ROOT, Bundle.empty());
        this.bundles = Map.copyOf(loaded);
        this.chainCache.clear();
    }

    private static Locale localeFromMatch(Matcher m) {
        String lang = m.group(1);
        if (lang == null) return Locale.ROOT;
        String country = m.group(2);
        return country == null
                ? Locale.of(lang.toLowerCase(Locale.ROOT))
                : Locale.of(lang.toLowerCase(Locale.ROOT), country.toUpperCase(Locale.ROOT));
    }

    // ── resolution ────────────────────────────────────────────────────────────────

    /**
     * The bundles to consult for {@code locale}, most specific first, always ending at the base bundle:
     * {@code lang_COUNTRY → lang → defaultLocale[ → its lang] → ROOT}, keeping only those present.
     */
    private List<Bundle> chainFor(Locale locale) {
        return chainCache.computeIfAbsent(locale == null ? Locale.ROOT : locale, this::buildChain);
    }

    private List<Bundle> buildChain(Locale locale) {
        LinkedHashSet<Locale> order = new LinkedHashSet<>();
        order.add(locale);
        if (!locale.getCountry().isEmpty()) {
            order.add(Locale.of(locale.getLanguage()));
        }
        Locale def = defaultLocale;
        if (!def.equals(Locale.ROOT)) {
            order.add(def);
            if (!def.getCountry().isEmpty()) {
                order.add(Locale.of(def.getLanguage()));
            }
        }
        order.add(Locale.ROOT);

        Map<Locale, Bundle> snapshot = bundles;
        List<Bundle> chain = new ArrayList<>(order.size());
        for (Locale candidate : order) {
            Bundle bundle = snapshot.get(candidate);
            if (bundle != null) {
                chain.add(bundle);
            }
        }
        if (chain.isEmpty()) {
            chain.add(snapshot.getOrDefault(Locale.ROOT, Bundle.empty()));
        }
        return List.copyOf(chain);
    }

    /** First bundle in the chain that defines {@code key}, or {@code null} if none does. */
    private String findRaw(Locale locale, String key) {
        for (Bundle bundle : chainFor(locale)) {
            String value = bundle.props.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String formatPattern(Locale locale, String pattern, String ns, Map<String, ?> values) {
        // escape literal braces
        pattern = pattern.replace("{{", LBR).replace("}}", RBR);

        // pre-render caller values (escaped unless wrapped in Rich)
        Map<String, String> userValues = new LinkedHashMap<>();
        if (values != null) {
            for (var e : values.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    userValues.put(e.getKey(), renderValue(e.getValue()));
                }
            }
        }

        List<Bundle> chain = chainFor(locale);
        Function<String, String> lookup = name -> {
            String user = userValues.get(name);
            if (user != null) return user;
            for (Bundle bundle : chain) {
                Map<String, String> nsMap = bundle.nsPh.get(ns);
                if (nsMap != null) {
                    String v = nsMap.get(name);
                    if (v != null) return v;
                }
                String global = bundle.globalPh.get(name);
                if (global != null) return global;
            }
            return null;
        };

        String out = expandString(pattern, lookup, MAX_EXPANSION_DEPTH);
        return out.replace(LBR, "{").replace(RBR, "}");
    }

    /**
     * Caller-supplied values are inert unless wrapped in {@link Rich}: tags are escaped so they render
     * literally, and braces are sentinel-encoded so a value like {@code {prefix}} can't trigger another
     * round of expansion.
     */
    private String renderValue(Object value) {
        if (value instanceof Rich rich) {
            return rich.value();
        }
        return mm.escapeTags(Objects.toString(value)).replace("{", LBR).replace("}", RBR);
    }

    private String expandString(String input, Function<String, String> lookup, int depth) {
        if (depth <= 0 || input.indexOf('{') < 0) return input;
        StringBuilder builder = new StringBuilder(input.length() + 16);
        Matcher m = PLACEHOLDER.matcher(input);
        boolean changed = false;
        while (m.find()) {
            String name = m.group(1);
            String repl = lookup.apply(name);
            if (repl == null) {
                m.appendReplacement(builder, Matcher.quoteReplacement("{" + name + "}"));
            } else {
                changed = true;
                m.appendReplacement(builder, Matcher.quoteReplacement(repl));
            }
        }
        m.appendTail(builder);
        return changed ? expandString(builder.toString(), lookup, depth - 1) : builder.toString();
    }

    private static String namespaceOf(String key) {
        int i = key.indexOf('.');
        return i > 0 ? key.substring(0, i) : "";
    }

    private static Map<String, Object> kvToMap(Object... kvPairs) {
        if ((kvPairs.length & 1) == 1) {
            throw new IllegalArgumentException("Placeholder arguments must be in pairs: key, value, …");
        }
        Map<String, Object> map = new LinkedHashMap<>(kvPairs.length / 2);
        for (int i = 0; i < kvPairs.length; i += 2) {
            Object k = kvPairs[i];
            if (!(k instanceof String)) {
                throw new IllegalArgumentException("Placeholder name at index " + i + " must be a String");
            }
            map.put((String) k, kvPairs[i + 1]);
        }
        return map;
    }

    /**
     * Builds a {@link Component} from a pattern using MiniMessage tag resolvers (the path behind
     * {@link #component}/{@link #componentOr}/{@link #send}, as opposed to the string-only
     * {@link #format}).
     *
     * <p>Each {@code {name}} placeholder becomes a generated MiniMessage tag bound to a resolver:</p>
     * <ul>
     *   <li>caller {@link ComponentLike} value → {@code Placeholder.component} (a formatted item/player
     *       name renders properly, instead of being {@code toString()}'d)</li>
     *   <li>caller {@link Rich} value → {@code Placeholder.parsed} (trusted markup)</li>
     *   <li>any other caller value → {@code Placeholder.unparsed} (safe literal text — no escaping hacks)</li>
     *   <li>{@code placeholder.*} palette entry → {@code Placeholder.parsed} (may carry markup and
     *       reference other placeholders, which MiniMessage resolves recursively)</li>
     * </ul>
     *
     * <p>The admin-facing {@code {a.b}}/camelCase placeholder names are preserved; the actual MiniMessage
     * tags are generated ({@code ph0}, {@code ph1}, …) so any name is a valid tag regardless of
     * MiniMessage's stricter tag-name rules. An unknown {@code {name}} is left literal. Caller values take
     * precedence over palette entries of the same name.</p>
     */
    private final class ResolverRender {
        private final List<Bundle> chain;
        private final OfflinePlayer papiPlayer;
        private final String ns;
        private final Map<String, ?> values;
        private final Map<String, String> nameToTag = new HashMap<>();
        private final List<TagResolver> resolvers = new ArrayList<>();

        ResolverRender(Locale locale, OfflinePlayer papiPlayer, String ns, Map<String, ?> values) {
            this.chain = chainFor(locale);
            this.papiPlayer = papiPlayer;
            this.ns = ns;
            this.values = values == null ? Map.of() : values;
        }

        Component render(String pattern) {
            String converted = convert(pattern);
            try {
                return mm.deserialize(converted, TagResolver.resolver(resolvers));
            } catch (RuntimeException e) {
                // A malformed pattern or a cyclic placeholder shouldn't take down the caller.
                log.warn("Failed to render message: {}", e.getMessage());
                return Component.text(pattern);
            }
        }

        /** Replace each known {@code {name}} with its generated {@code <tag>}; leave unknowns literal. */
        private String convert(String text) {
            // Resolve %papi% tokens in operator-authored text (pattern + palette + Rich) — never in the
            // inert caller values, which bypass convert() and are inserted as escaped literals.
            text = papi.resolve(papiPlayer, text);
            text = text.replace("{{", LBR).replace("}}", RBR);
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder sb = new StringBuilder(text.length() + 16);
            while (m.find()) {
                String name = m.group(1);
                String tag = tagFor(name);
                m.appendReplacement(sb,
                        Matcher.quoteReplacement(tag != null ? "<" + tag + ">" : "{" + name + "}"));
            }
            m.appendTail(sb);
            return sb.toString().replace(LBR, "{").replace(RBR, "}");
        }

        /** The generated tag for a known placeholder (registering its resolver), or {@code null}. */
        private String tagFor(String name) {
            String existing = nameToTag.get(name);
            if (existing != null) return existing;

            Object value = values.get(name);
            if (value != null) {
                String tag = newTag(name);
                if (value instanceof Rich rich) {
                    resolvers.add(Placeholder.parsed(tag, convert(rich.value())));
                } else if (value instanceof ComponentLike componentLike) {
                    resolvers.add(Placeholder.component(tag, componentLike));
                } else {
                    resolvers.add(Placeholder.unparsed(tag, Objects.toString(value)));
                }
                return tag;
            }

            String palette = paletteValue(name);
            if (palette != null) {
                String tag = newTag(name);
                resolvers.add(Placeholder.parsed(tag, convert(palette)));
                return tag;
            }
            return null;
        }

        private String newTag(String name) {
            String tag = "ph" + nameToTag.size();
            nameToTag.put(name, tag);   // register before any recursive convert() to break cycles
            return tag;
        }

        private String paletteValue(String name) {
            for (Bundle bundle : chain) {
                Map<String, String> nsMap = bundle.nsPh.get(ns);
                if (nsMap != null) {
                    String v = nsMap.get(name);
                    if (v != null) return v;
                }
                String global = bundle.globalPh.get(name);
                if (global != null) return global;
            }
            return null;
        }
    }

    /**
     * One loaded locale's messages: the raw properties plus its split placeholder maps
     * ({@code placeholder.*} → global; {@code <ns>.placeholder.*} → per-namespace). Placeholder values
     * are stored raw; nested references are expanded recursively at render time.
     */
    private record Bundle(Properties props, Map<String, String> globalPh, Map<String, Map<String, String>> nsPh) {

        static Bundle empty() {
            return new Bundle(new Properties(), Map.of(), Map.of());
        }

        static Bundle of(Properties props) {
            Map<String, String> global = new LinkedHashMap<>();
            Map<String, Map<String, String>> ns = new LinkedHashMap<>();
            for (String key : props.stringPropertyNames()) {
                String val = props.getProperty(key);
                if (key.startsWith("placeholder.")) {
                    global.put(key.substring("placeholder.".length()), val);
                } else {
                    int dot = key.indexOf('.');
                    if (dot > 0 && key.regionMatches(dot + 1, "placeholder.", 0, "placeholder.".length())) {
                        String nsName = key.substring(0, dot);
                        String phName = key.substring(dot + 1 + "placeholder.".length());
                        ns.computeIfAbsent(nsName, k -> new LinkedHashMap<>()).put(phName, val);
                    }
                }
            }
            return new Bundle(props, global, ns);
        }
    }
}
