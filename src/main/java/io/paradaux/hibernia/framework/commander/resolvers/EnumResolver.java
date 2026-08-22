package io.paradaux.hibernia.framework.commander.resolvers;

import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves any enum-typed parameter by constant name, matched case-insensitively, and suggests the
 * constants.
 *
 * <p>Synthesised on demand for whichever enum a handler declares, so an enum argument needs no
 * per-type resolver. A plugin that wants different behaviour — aliases, a subset of the constants —
 * registers its own resolver for that enum, which takes precedence as an exact type match.</p>
 *
 * @param <E> the enum type
 */
public final class EnumResolver<E extends Enum<E>> implements ParameterResolver<E> {

    private final Class<E> type;

    public EnumResolver(Class<E> type) {
        this.type = type;
    }

    @Override
    public Class<E> type() {
        return type;
    }

    @Override
    public Optional<E> resolve(String token, CommandSender sender) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(token)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (E constant : type.getEnumConstants()) {
            String name = constant.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(lowered)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }
}
