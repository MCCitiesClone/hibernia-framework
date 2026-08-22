package io.paradaux.hibernia.framework.commander;

import java.util.Locale;

/**
 * One token of a parsed route pattern: a literal word, a required {@code <arg>} or
 * an optional {@code [arg]}. Package-private value type shared by the binder,
 * tree-builder and validator.
 */
record Segment(SegKind kind, String token) {

    enum SegKind { LITERAL, ARG, OPTIONAL_ARG }

    static Segment literal(String s) {
        return new Segment(SegKind.LITERAL, s.toLowerCase(Locale.ROOT));
    }

    static Segment arg(String name) {
        return new Segment(SegKind.ARG, name);
    }

    static Segment optionalArg(String name) {
        return new Segment(SegKind.OPTIONAL_ARG, name);
    }

    boolean literal() {
        return kind == SegKind.LITERAL;
    }

    boolean optionalArg() {
        return kind == SegKind.OPTIONAL_ARG;
    }
}
