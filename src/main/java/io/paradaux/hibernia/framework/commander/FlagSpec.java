package io.paradaux.hibernia.framework.commander;

import java.util.ArrayList;
import java.util.List;

/**
 * A bound {@code @Flag} parameter: its canonical name, accepted aliases, target type
 * and default. Package-private value type with package-visible fields, matching
 * {@link Param}, so the binder, tree-builder and runtime extractor read it directly.
 */
final class FlagSpec {

    final String name;
    final List<String> aliases;
    final Class<?> type;
    final boolean presence;
    final boolean sanitize;
    final String defaultValue;

    FlagSpec(String name, List<String> aliases, Class<?> type, boolean presence,
             boolean sanitize, String defaultValue) {
        this.name = name;
        this.aliases = List.copyOf(aliases);
        this.type = type;
        this.presence = presence;
        this.sanitize = sanitize;
        this.defaultValue = defaultValue;
    }

    /** Every name this flag answers to, canonical name first. */
    List<String> allNames() {
        if (aliases.isEmpty()) {
            return List.of(name);
        }
        List<String> names = new ArrayList<>(aliases.size() + 1);
        names.add(name);
        names.addAll(aliases);
        return List.copyOf(names);
    }

    boolean answersTo(String candidate) {
        if (name.equals(candidate)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
