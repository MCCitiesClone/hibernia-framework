package io.paradaux.hibernia.framework.commander;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tokenizer and parser for the trailing {@code --name value} segment of a flagged route.
 *
 * <p>Flags reach Brigadier as a single greedy string node rather than as a chain of
 * literal/argument nodes. That is deliberate: chaining would need the nodes to redirect
 * back to a dispatch node so flags could be given in any order, and a Brigadier redirect
 * starts a fresh {@code CommandContext} — the route's own positional arguments, parsed
 * before the redirect, become unreachable from the context that finally executes. One
 * greedy node keeps every argument in a single context, and this class supplies the
 * grammar that Brigadier would otherwise have provided: {@link #parse} for execution and
 * {@link #completionAt} for tab-completion, both reading the same token stream so what
 * the client suggests is always what the server will accept.</p>
 *
 * <p>Accepted forms: {@code --name value}, {@code --name=value}, {@code --name} (presence
 * flags), and double-quoted values for arguments containing spaces. A repeated flag takes
 * its last value.</p>
 */
final class FlagTail {

    /** Raised for input the user can fix; rendered to the sender rather than logged. */
    static final class FlagSyntaxException extends IllegalArgumentException {
        FlagSyntaxException(String message) {
            super(message);
        }
    }

    /** One token of the tail, with its start offset in the raw string for suggestion anchoring. */
    record Token(String text, int start, boolean quoted) {
    }

    /** What the caret currently sits on, so the suggester knows what to offer. */
    enum CompletionKind {
        /** Completing a flag name — offer the flags not yet used. */
        FLAG_NAME,
        /** Completing a value for {@link Completion#flag()} — delegate to its resolver. */
        FLAG_VALUE
    }

    /**
     * Where the caret is and what belongs there.
     *
     * @param kind      what to suggest
     * @param flag      the flag whose value is being typed, for {@link CompletionKind#FLAG_VALUE}
     * @param prefix    the partial text already typed at the caret
     * @param start     offset of {@code prefix} within the raw tail, for suggestion anchoring
     * @param usedNames flag names already present, so the suggester can omit them
     */
    record Completion(CompletionKind kind, FlagSpec flag, String prefix, int start, List<String> usedNames) {
    }

    private FlagTail() {
    }

    /**
     * Splits the raw tail into tokens, honouring double quotes. An unterminated quote is
     * treated as running to the end of input — mid-typing that is the normal state, and
     * failing on it would break completion for every quoted value.
     */
    static List<Token> tokenize(String raw) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = raw.length();
        while (i < length) {
            while (i < length && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            if (i >= length) {
                break;
            }
            int start = i;
            StringBuilder text = new StringBuilder();
            boolean quoted = false;
            if (raw.charAt(i) == '"') {
                quoted = true;
                i++;
                while (i < length && raw.charAt(i) != '"') {
                    text.append(raw.charAt(i));
                    i++;
                }
                // Skip the closing quote when there is one; an unterminated quote just ends here.
                if (i < length) {
                    i++;
                }
            } else {
                while (i < length && !Character.isWhitespace(raw.charAt(i))) {
                    text.append(raw.charAt(i));
                    i++;
                }
            }
            tokens.add(new Token(text.toString(), start, quoted));
        }
        return tokens;
    }

    /** True when a token is a flag name rather than a value: {@code --x}, but not {@code -1} or a quoted "--x". */
    private static boolean isFlagName(Token token) {
        if (token.quoted()) {
            return false;
        }
        String text = token.text();
        return text.length() > 2 && text.startsWith("--");
    }

    /** Strips the leading dashes and any {@code =value} suffix from a flag-name token. */
    private static String nameOf(String tokenText) {
        String name = tokenText.startsWith("--") ? tokenText.substring(2) : tokenText;
        int equals = name.indexOf('=');
        return equals >= 0 ? name.substring(0, equals) : name;
    }

    private static FlagSpec find(List<FlagSpec> flags, String name) {
        for (FlagSpec flag : flags) {
            if (flag.answersTo(name)) {
                return flag;
            }
        }
        return null;
    }

    /**
     * Parses the tail into canonical-name → raw-value pairs. Presence flags map to their
     * own name as the value; their mere presence is the signal.
     *
     * @throws FlagSyntaxException on an unknown flag, or a value flag given no value
     */
    static Map<String, String> parse(String raw, List<FlagSpec> flags) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        List<Token> tokens = tokenize(raw);
        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            if (!isFlagName(token)) {
                throw new FlagSyntaxException("Expected a --flag but found '" + token.text() + "'");
            }
            String name = nameOf(token.text());
            FlagSpec flag = find(flags, name);
            if (flag == null) {
                throw new FlagSyntaxException("Unknown flag '--" + name + "'");
            }
            int equals = token.text().indexOf('=');
            if (equals >= 0) {
                String inline = token.text().substring(equals + 1);
                if (flag.presence) {
                    throw new FlagSyntaxException("Flag '--" + name + "' takes no value");
                }
                if (inline.isEmpty()) {
                    throw new FlagSyntaxException("Flag '--" + name + "' requires a value");
                }
                values.put(flag.name, inline);
                i++;
                continue;
            }
            if (flag.presence) {
                values.put(flag.name, flag.name);
                i++;
                continue;
            }
            if (i + 1 >= tokens.size() || isFlagName(tokens.get(i + 1))) {
                throw new FlagSyntaxException("Flag '--" + name + "' requires a value");
            }
            values.put(flag.name, tokens.get(i + 1).text());
            i += 2;
        }
        return values;
    }

    /**
     * Works out what the caret at the end of {@code raw} is typing. Unlike {@link #parse}
     * this never throws — half-typed input is the normal case while completing, so an
     * unknown or incomplete flag simply falls back to suggesting flag names.
     */
    static Completion completionAt(String raw, List<FlagSpec> flags) {
        String text = raw == null ? "" : raw;
        List<Token> tokens = tokenize(text);
        boolean atTokenStart = text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1));

        // The token the caret is inside, or null when the caret sits after a space.
        Token current = atTokenStart || tokens.isEmpty() ? null : tokens.get(tokens.size() - 1);
        List<Token> complete = current == null ? tokens : tokens.subList(0, tokens.size() - 1);

        List<String> used = new ArrayList<>();
        for (Token token : complete) {
            if (isFlagName(token)) {
                String name = nameOf(token.text());
                FlagSpec flag = find(flags, name);
                if (flag != null) {
                    used.add(flag.name);
                }
            }
        }

        // Caret inside a token that itself carries an inline value: --name=val
        if (current != null && isFlagName(current) && current.text().indexOf('=') >= 0) {
            String name = nameOf(current.text());
            FlagSpec flag = find(flags, name);
            int equals = current.text().indexOf('=');
            if (flag != null && !flag.presence) {
                return new Completion(CompletionKind.FLAG_VALUE, flag,
                        current.text().substring(equals + 1),
                        current.start() + equals + 1, used);
            }
        }

        // Caret is positioned to supply the value of the preceding value-flag.
        Token previous = complete.isEmpty() ? null : complete.get(complete.size() - 1);
        if (previous != null && isFlagName(previous) && previous.text().indexOf('=') < 0) {
            FlagSpec flag = find(flags, nameOf(previous.text()));
            if (flag != null && !flag.presence) {
                // The flag being valued is not yet "used up" for name-suggestion purposes.
                used.remove(flag.name);
                String prefix = current == null ? "" : current.text();
                int start = current == null ? text.length() : current.start();
                return new Completion(CompletionKind.FLAG_VALUE, flag, prefix, start, used);
            }
        }

        String prefix = current == null ? "" : current.text();
        int start = current == null ? text.length() : current.start();
        return new Completion(CompletionKind.FLAG_NAME, null, prefix, start, used);
    }
}
