package io.paradaux.hibernia.framework.commander.resolvers;

import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;

/**
 * Resolves {@code double} arguments.
 *
 * <p>Brigadier's own double node already delivers a typed value, so this exists for the paths that
 * see the raw token: a flag, or a declared default. Without it a {@code double} parameter received
 * a String and the invocation failed with an argument type mismatch.</p>
 */
public final class DoubleResolver implements ParameterResolver<Double> {

    @Override
    public Class<Double> type() {
        return Double.class;
    }

    @Override
    public Optional<Double> resolve(String token, CommandSender sender) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            // Thousands separators are ordinary in prices typed by hand.
            return Optional.of(Double.parseDouble(token.replace(",", "").trim()));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        return List.of();
    }
}
