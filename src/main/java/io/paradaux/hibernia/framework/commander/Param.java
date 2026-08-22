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
    final double min;
    final double max;

    private Param(boolean sender, boolean optional, boolean sanitize, boolean greedy,
                  boolean flag, Class<?> type, String name, Object defaultValue,
                  double min, double max) {
        this.sender = sender;
        this.optional = optional;
        this.sanitize = sanitize;
        this.greedy = greedy;
        this.flag = flag;
        this.type = type;
        this.name = name;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
    }

    static Param sender(Class<?> t) {
        return new Param(true, false, true, false, false, t, "", null,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    static Param required(Class<?> t, String n, boolean sanitize, double min, double max) {
        return new Param(false, false, sanitize, false, false, t, n, null, min, max);
    }

    /** Unbounded convenience forms; a numeric argument without an explicit range uses these. */
    static Param required(Class<?> t, String n, boolean sanitize) {
        return required(t, n, sanitize, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    static Param optional(Class<?> t, String n, Object def, boolean sanitize) {
        return optional(t, n, def, sanitize, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    static Param flag(Class<?> t, String n, boolean sanitize, Object def) {
        return flag(t, n, sanitize, def, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    static Param greedy(Class<?> t, String n, boolean sanitize) {
        return new Param(false, false, sanitize, true, false, t, n, null,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    static Param optional(Class<?> t, String n, Object def, boolean sanitize,
                          double min, double max) {
        return new Param(false, true, sanitize, false, false, t, n, def, min, max);
    }

    static Param flag(Class<?> t, String n, boolean sanitize, Object def,
                      double min, double max) {
        return new Param(false, true, sanitize, false, true, t, n, def, min, max);
    }
}
