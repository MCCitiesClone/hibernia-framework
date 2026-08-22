package io.paradaux.hibernia.framework.commander;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Per-root registration state, shared by every handler contributing to the root. */
final class RootSpec {
    final LiteralArgumentBuilder<CommandSourceStack> builder;
    final Set<String> classPerms = new LinkedHashSet<>();
    final Map<String, String> routesSeen = new HashMap<>();
    final Map<String, ArgKind> argKinds = new HashMap<>();
    final Map<String, String> argChildAt = new HashMap<>();
    boolean openAccess;
    String description = "";

    RootSpec(LiteralArgumentBuilder<CommandSourceStack> builder) {
        this.builder = builder;
    }
}
