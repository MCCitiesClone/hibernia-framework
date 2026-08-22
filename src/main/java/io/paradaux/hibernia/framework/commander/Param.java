package io.paradaux.hibernia.framework.commander;

/**
 * A bound handler-method parameter: a {@code @Sender} injection, a required/optional
 * {@code @Arg}, a {@code @GreedyArg}, or a {@code @Flag}. Package-private value type with
 * package-visible fields so the binder, tree-builder and runtime extractor can read it
 * directly.
 *
 * <p>Flag parameters keep their slot in the parameter list even though they are absent from
 * the route pattern: the extractor fills {@code values} in declaration order before
 * {@code Method#invoke}, so every parameter needs an entry. The flag's own metadata lives in
 * the matching {@link FlagSpec}.</p>
 */
final class Param {
    final boolean sender;
    final boolean optional;
    final boolean sanitize;
    final boolean greedy;
    final boolean flag;
    final Class<?> type;
    final String name;
    final Object defaultValue;

    private Param(boolean sender, boolean optional, boolean sanitize, boolean greedy,
                  boolean flag, Class<?> type, String name, Object defaultValue) {
        this.sender = sender;
        this.optional = optional;
        this.sanitize = sanitize;
        this.greedy = greedy;
        this.flag = flag;
        this.type = type;
        this.name = name;
        this.defaultValue = defaultValue;
    }

    static Param sender(Class<?> t) {
        return new Param(true, false, true, false, false, t, "", null);
    }

    static Param required(Class<?> t, String n, boolean sanitize) {
        return new Param(false, false, sanitize, false, false, t, n, null);
    }

    static Param greedy(Class<?> t, String n, boolean sanitize) {
        return new Param(false, false, sanitize, true, false, t, n, null);
    }

    static Param optional(Class<?> t, String n, Object def, boolean sanitize) {
        return new Param(false, true, sanitize, false, false, t, n, def);
    }

    static Param flag(Class<?> t, String n, boolean sanitize, Object def) {
        return new Param(false, true, sanitize, false, true, t, n, def);
    }
}
