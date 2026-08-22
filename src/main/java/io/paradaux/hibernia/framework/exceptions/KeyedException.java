package io.paradaux.hibernia.framework.exceptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implemented by the framework's semantic exceptions so a thrown error can carry
 * a {@code messages.properties} key (plus placeholder values) instead of a
 * hard-coded human string.
 *
 * <p>The exception's message <em>is</em> the key: a handler (or service layer)
 * can {@code throw new NotFoundException("myplugin.account.not-found", "name", who)}
 * and {@link io.paradaux.hibernia.framework.commander.CommandManager} resolves that
 * key against the plugin's bound {@link io.paradaux.hibernia.framework.i18n.Message}
 * bean, in the recipient's locale, with the placeholders expanded.</p>
 *
 * <p><strong>Back-compatible.</strong> When the message is <em>not</em> a defined
 * key — e.g. an existing {@code throw new NotFoundException("Account not found")}
 * — the framework falls back to its generic {@code hibernia.error.*} rendering with
 * the raw text, exactly as before. So plain-string throw-sites need no change.</p>
 */
public interface KeyedException {

    /** The {@code messages.properties} key to resolve (the exception's message doubles as the key). */
    String messageKey();

    /** Placeholder values to expand in the resolved message; empty when none were supplied. */
    Map<String, ?> placeholders();

    /**
     * Build a placeholder map from flat {@code key, value, key, value, …} pairs,
     * mirroring {@link io.paradaux.hibernia.framework.i18n.Message}'s varargs convention.
     */
    static Map<String, Object> pairs(Object... kv) {
        if ((kv.length & 1) == 1) {
            throw new IllegalArgumentException("Placeholder arguments must be in pairs: key, value, …");
        }
        Map<String, Object> map = new LinkedHashMap<>(kv.length / 2);
        for (int i = 0; i < kv.length; i += 2) {
            if (!(kv[i] instanceof String name)) {
                throw new IllegalArgumentException("Placeholder name at index " + i + " must be a String");
            }
            map.put(name, kv[i + 1]);
        }
        return map;
    }
}
