package io.paradaux.hibernia.framework.upgrade;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Additive line-based merge of a bundled {@code messages.properties} default into
 * an operator's on-disk copy (PAR-252).
 *
 * <p>For every key the jar default declares that the on-disk file is missing, the
 * jar's entry — together with the comment/blank lines immediately above it — is
 * appended verbatim to the end of the operator's file. Every existing operator
 * value, comment, ordering and any operator-added key is left byte-for-byte
 * untouched; a run with no new keys returns the input unchanged.</p>
 *
 * <p>A line-based append is used deliberately: {@link java.util.Properties#store}
 * would drop comments and reorder the whole file. The existing key set is read via
 * {@link Properties} (so escaping and continuation lines are interpreted exactly as
 * the loader sees them), while insertion preserves the jar's raw formatting.</p>
 */
public final class PropertiesDefaultsMerger {

    private PropertiesDefaultsMerger() {
    }

    /**
     * @return the merged file content, or {@code onDisk} unchanged when the jar
     *         default introduces no new keys.
     */
    public static String merge(String onDisk, String jarDefault) {
        Set<String> existing = keysOf(onDisk);
        String eol = onDisk.contains("\r\n") ? "\r\n" : "\n";

        List<String> lines = splitLines(jarDefault);
        List<String> pending = new ArrayList<>();   // comment/blank lines preceding the next entry
        List<String> appended = new ArrayList<>();
        boolean changed = false;

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String lead = stripLeading(line);
            if (lead.isEmpty() || lead.charAt(0) == '#' || lead.charAt(0) == '!') {
                pending.add(line);
                i++;
                continue;
            }

            // Start of a key entry — gather any continuation lines.
            List<String> entry = new ArrayList<>();
            entry.add(line);
            int j = i;
            while (continues(lines.get(j)) && j + 1 < lines.size()) {
                j++;
                entry.add(lines.get(j));
            }

            String key = parseKey(String.join("\n", entry));
            if (key != null && !existing.contains(key)) {
                appended.addAll(pending);
                appended.addAll(entry);
                changed = true;
            }
            pending.clear();
            i = j + 1;
        }

        if (!changed) {
            return onDisk;
        }

        StringBuilder out = new StringBuilder(onDisk);
        if (!onDisk.isEmpty() && !onDisk.endsWith("\n") && !onDisk.endsWith("\r")) {
            out.append(eol);
        }
        out.append("# --- Added by HiberniaFramework on upgrade ---").append(eol);
        for (String l : appended) {
            out.append(l).append(eol);
        }
        return out.toString();
    }

    private static Set<String> keysOf(String content) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (IOException | RuntimeException e) {
            // A malformed on-disk file shouldn't crash boot; treat it as having no keys
            // (the worst case is re-appending defaults, which the operator can reconcile).
            // Note: Properties.load throws an unchecked IllegalArgumentException on a malformed
            // \\uXXXX escape, not IOException — so the catch must include RuntimeException.
            return Set.of();
        }
        return props.stringPropertyNames();
    }

    private static List<String> splitLines(String content) {
        List<String> out = new ArrayList<>();
        for (String l : content.split("\n", -1)) {
            out.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        }
        // split with a trailing newline yields a final empty element; drop it so we
        // don't treat a phantom blank line as pending content.
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\f')) {
            i++;
        }
        return s.substring(i);
    }

    /** Whether a physical line ends with an odd number of backslashes (a continuation). */
    private static boolean continues(String line) {
        int back = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            back++;
        }
        return (back & 1) == 1;
    }

    /**
     * Extract the property key from a logical line (continuations already joined with
     * {@code \n}), applying the {@link Properties} rules: skip leading whitespace, read
     * up to the first unescaped {@code =}, {@code :} or whitespace, unescaping {@code \x}.
     */
    private static String parseKey(String logical) {
        // Drop continuation backslash-newline-and-leading-whitespace sequences.
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < logical.length(); i++) {
            char c = logical.charAt(i);
            if (c == '\\' && i + 1 < logical.length() && logical.charAt(i + 1) == '\n') {
                i++; // skip the newline
                while (i + 1 < logical.length()
                        && (logical.charAt(i + 1) == ' ' || logical.charAt(i + 1) == '\t' || logical.charAt(i + 1) == '\f')) {
                    i++;
                }
            } else {
                joined.append(c);
            }
        }

        String s = stripLeading(joined.toString());
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                key.append(s.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\f') {
                break;
            }
            key.append(c);
        }
        return key.isEmpty() ? null : key.toString();
    }
}
