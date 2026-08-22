package io.paradaux.hibernia.framework.commander;

import io.paradaux.hibernia.framework.commander.annotations.Async;

import java.lang.reflect.Method;
import java.util.List;

/**
 * A validated binding of a handler method to a route: its parsed path, bound
 * parameters, effective permission, description and async flag. Package-private with
 * package-visible fields so the orchestrator, tree-builder and extractor read it
 * directly.
 */
class RouteBinding {
    final Object instance;
    final Method method;
    final List<Segment> path;
    final List<Param> params;
    final String permission;
    final String description;
    final String rawPattern;
    final List<FlagSpec> flags;
    final boolean async;

    RouteBinding(Object instance, Method method, List<Segment> path, List<Param> params,
                 String permission, String description, String rawPattern, List<FlagSpec> flags) {
        this.instance = instance;
        this.method = method;
        this.path = path;
        this.params = params;
        this.permission = permission;
        this.description = description;
        this.rawPattern = rawPattern;
        this.flags = List.copyOf(flags);
        this.method.setAccessible(true);
        this.async = method.isAnnotationPresent(Async.class);
    }

    /** Human-readable identifier for diagnostics (conflict messages, error logging). */
    String describe() {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName()
                + " (route '" + rawPattern + "')";
    }
}
