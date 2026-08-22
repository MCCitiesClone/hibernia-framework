package io.paradaux.hibernia.framework.commander;

import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a {@code @Route} pattern, binds the handler method's parameters to it and
 * validates the result at registration time (PAR fail-loud contract): every
 * placeholder must have a matching parameter, every required {@code @Arg} must appear
 * in the route, optional segments must form the tail, and greedy arguments must be
 * terminal. A mismatch throws {@link IllegalStateException} rather than silently
 * dropping the route.
 */
final class RouteBinder {

    RouteBinding bind(Object instance, Method m, Route r, String classPerm) {
        String raw = r.value().trim();
        List<String> parts = raw.isEmpty() ? List.of() : List.of(raw.split("\\s+"));

        List<Segment> segments = new ArrayList<>();
        for (String p : parts) {
            if (p.startsWith("<") && p.endsWith(">")) {
                segments.add(Segment.arg(p.substring(1, p.length() - 1)));
            } else if (p.startsWith("[") && p.endsWith("]")) {
                segments.add(Segment.optionalArg(p.substring(1, p.length() - 1)));
            } else {
                segments.add(Segment.literal(p));
            }
        }

        List<Param> params = new ArrayList<>();
        List<FlagSpec> flags = new ArrayList<>();
        boolean foundGreedy = false;
        for (Parameter rp : m.getParameters()) {
            boolean isSender = rp.isAnnotationPresent(Sender.class);
            Arg arg = rp.getAnnotation(Arg.class);
            OptionalArg opt = rp.getAnnotation(OptionalArg.class);
            GreedyArg greedy = rp.getAnnotation(GreedyArg.class);
            Flag flag = rp.getAnnotation(Flag.class);

            if (foundGreedy && !isSender) {
                throw new IllegalStateException("@GreedyArg must be the last argument in the route on " + m);
            }

            if (isSender) params.add(Param.sender(rp.getType()));
            else if (flag != null) {
                FlagSpec spec = bindFlag(m, rp.getType(), flag);
                flags.add(spec);
                params.add(Param.flag(rp.getType(), spec.name, flag.sanitize(), spec.defaultValue));
            }
            else if (greedy != null) {
                foundGreedy = true;
                params.add(Param.greedy(rp.getType(), greedy.value(), greedy.sanitize()));
            }
            else if (arg != null) params.add(Param.required(rp.getType(), arg.value(), arg.sanitize()));
            else if (opt != null) {
                if (rp.getType().isPrimitive() && opt.defaultValue().isEmpty()) {
                    throw new IllegalStateException("@OptionalArg(\"" + opt.value() + "\") on " + m
                            + " has a primitive type but no defaultValue; an omitted argument would be null."
                            + " Provide a defaultValue or use the boxed type.");
                }
                params.add(Param.optional(rp.getType(), opt.value(), opt.defaultValue(), opt.sanitize()));
            }
            else throw new IllegalStateException("Parameter missing @Sender/@Arg/@OptionalArg/@GreedyArg/@Flag on " + m);
        }

        validateRoute(m, raw, segments, params);
        validateFlags(m, raw, segments, params, flags);

        String methodPerm = Optional.ofNullable(m.getAnnotation(Permission.class)).map(Permission::value).orElse(null);
        String effectivePerm = methodPerm != null ? methodPerm : classPerm;

        String description = Optional.ofNullable(m.getAnnotation(Description.class)).map(Description::value).orElse("");

        return new RouteBinding(instance, m, segments, params, effectivePerm, description, raw, flags);
    }

    /**
     * Registration-time validation: every placeholder must have a matching
     * parameter, every required parameter must appear in the route, optional
     * segments must form the tail, and greedy arguments must be terminal.
     * Failing loud here is the point — a mismatch that slipped through used to
     * silently drop the rest of the route from the command tree.
     */
    private void validateRoute(Method m, String raw, List<Segment> segments, List<Param> params) {
        String where = m.getDeclaringClass().getSimpleName() + "#" + m.getName();
        Set<String> seenNames = new HashSet<>();
        boolean optionalTail = false;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (seg.literal()) {
                if (optionalTail) {
                    throw new IllegalStateException("Route '" + raw + "' on " + where
                            + ": literal '" + seg.token() + "' cannot follow an optional [segment]");
                }
                continue;
            }
            if (!seenNames.add(seg.token())) {
                throw new IllegalStateException("Route '" + raw + "' on " + where
                        + " uses argument name '" + seg.token() + "' more than once");
            }
            Param param = findParamByName(params, seg.token());
            if (param == null) {
                throw new IllegalStateException("Route '" + raw + "' on " + where
                        + " references argument '" + seg.token() + "' but the method has no"
                        + " @Arg/@OptionalArg/@GreedyArg parameter with that name");
            }
            if (seg.optionalArg()) {
                if (!param.optional) {
                    throw new IllegalStateException("Route '" + raw + "' on " + where
                            + ": [" + seg.token() + "] requires an @OptionalArg parameter (found a required one)");
                }
                optionalTail = true;
            } else if (optionalTail) {
                throw new IllegalStateException("Route '" + raw + "' on " + where
                        + ": required <" + seg.token() + "> cannot follow an optional [segment]");
            }
            if (param.greedy && i != segments.size() - 1) {
                throw new IllegalStateException("Route '" + raw + "' on " + where
                        + ": greedy argument <" + seg.token() + "> must be the last segment");
            }
        }

        for (Param param : params) {
            if (param.sender || param.optional) continue;
            boolean inRoute = segments.stream().anyMatch(s -> !s.literal() && s.token().equals(param.name));
            if (!inRoute) {
                throw new IllegalStateException("@Arg(\"" + param.name + "\") on " + where
                        + " does not appear in route '" + raw + "'; add <" + param.name
                        + "> to the route or make the parameter @OptionalArg");
            }
        }
    }

    /**
     * Normalises and checks a single {@code @Flag} declaration. Leading dashes are optional
     * in the annotation and stripped here so {@code @Flag("page")} and {@code @Flag("--page")}
     * are the same flag.
     */
    private FlagSpec bindFlag(Method m, Class<?> type, Flag flag) {
        String where = m.getDeclaringClass().getSimpleName() + "#" + m.getName();
        String name = stripDashes(flag.value());
        if (name.isEmpty()) {
            throw new IllegalStateException("@Flag on " + where + " has a blank name");
        }
        rejectIllegalFlagName(where, name);

        List<String> aliases = new ArrayList<>();
        for (String rawAlias : flag.aliases()) {
            String alias = stripDashes(rawAlias);
            if (alias.isEmpty()) {
                throw new IllegalStateException("@Flag(\"" + name + "\") on " + where + " has a blank alias");
            }
            rejectIllegalFlagName(where, alias);
            aliases.add(alias);
        }

        if (flag.presence()) {
            if (type != boolean.class && type != Boolean.class) {
                throw new IllegalStateException("@Flag(\"" + name + "\", presence = true) on " + where
                        + " must be declared on a boolean parameter, but is " + type.getSimpleName());
            }
        } else if (type.isPrimitive() && flag.defaultValue().isEmpty()) {
            throw new IllegalStateException("@Flag(\"" + name + "\") on " + where
                    + " has a primitive type but no defaultValue; an omitted flag would be null."
                    + " Provide a defaultValue or use the boxed type.");
        }

        return new FlagSpec(name, aliases, type, flag.presence(), flag.sanitize(), flag.defaultValue());
    }

    private static String stripDashes(String raw) {
        String trimmed = raw.trim();
        while (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static void rejectIllegalFlagName(String where, String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || c == '=' || c == '"') {
                throw new IllegalStateException("@Flag name '" + name + "' on " + where
                        + " may not contain whitespace, '=' or a quote");
            }
        }
    }

    /**
     * Registration-time validation for the flag set: no name may be claimed twice (across
     * canonical names and aliases alike), and no flag may shadow a positional argument of the
     * same route — both would resolve ambiguously and are far easier to diagnose here than
     * from a mis-bound value at runtime.
     */
    private void validateFlags(Method m, String raw, List<Segment> segments,
                               List<Param> params, List<FlagSpec> flags) {
        if (flags.isEmpty()) {
            return;
        }
        String where = m.getDeclaringClass().getSimpleName() + "#" + m.getName();

        // A greedy argument consumes the rest of the line, flag tail included, so the two
        // cannot coexist on one route. Caught here rather than as a baffling runtime parse.
        for (Param param : params) {
            if (param.greedy) {
                throw new IllegalStateException("Route '" + raw + "' on " + where
                        + " combines @GreedyArg <" + param.name + "> with flags; the greedy"
                        + " argument would consume them. Use flags or a greedy argument, not both.");
            }
        }

        Set<String> claimed = new HashSet<>();
        for (FlagSpec flag : flags) {
            for (String name : flag.allNames()) {
                if (!claimed.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("Route '" + raw + "' on " + where
                            + " declares the flag name '--" + name + "' more than once");
                }
            }
            for (Segment seg : segments) {
                if (!seg.literal() && seg.token().equals(flag.name)) {
                    throw new IllegalStateException("Route '" + raw + "' on " + where
                            + " declares both an argument and a flag named '" + flag.name + "'");
                }
            }
        }
    }

    static Param findParamByName(List<Param> params, String name) {
        for (Param p : params) {
            if (!p.sender && p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }
}
