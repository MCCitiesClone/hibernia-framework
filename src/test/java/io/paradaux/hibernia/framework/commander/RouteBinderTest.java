package io.paradaux.hibernia.framework.commander;

import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct registration-time validation tests for {@link RouteBinder} — the parse/bind/validate
 * collaborator split out of {@code CommandManager}.
 */
class RouteBinderTest {

    private final RouteBinder binder = new RouteBinder();

    static class Handler {
        @Route("dup <x> <x>")
        public void duplicateArgName(@Arg("x") String x) {
        }

        @Route("[x]")
        public void optionalSegmentForRequiredParam(@Arg("x") String x) {
        }

        @Route("[a] <b>")
        public void requiredAfterOptional(@OptionalArg("a") String a, @Arg("b") String b) {
        }

        @Route("<g> tail")
        public void greedyNotLastSegment(@GreedyArg("g") String g) {
        }

        @Permission("method.perm")
        @Description("does a thing")
        @Route("info <x>")
        public void annotated(@Arg("x") String x) {
        }

        @Route("ok [a]")
        public void optionalTail(@Sender CommandSender sender,
                                 @OptionalArg(value = "a", defaultValue = "1") String a) {
        }
    }

    private RouteBinding bind(String methodName, Class<?>... params) throws Exception {
        Method method = Handler.class.getDeclaredMethod(methodName, params);
        Route route = method.getAnnotation(Route.class);
        return binder.bind(new Handler(), method, route, "class.perm");
    }

    @Test
    void rejectsDuplicateArgumentName() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bind("duplicateArgName", String.class));
        assertTrue(ex.getMessage().contains("more than once"), ex.getMessage());
    }

    @Test
    void rejectsOptionalSegmentForRequiredParameter() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bind("optionalSegmentForRequiredParam", String.class));
        assertTrue(ex.getMessage().contains("requires an @OptionalArg"), ex.getMessage());
    }

    @Test
    void rejectsRequiredSegmentAfterOptional() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bind("requiredAfterOptional", String.class, String.class));
        assertTrue(ex.getMessage().contains("cannot follow an optional"), ex.getMessage());
    }

    @Test
    void rejectsGreedyArgumentThatIsNotLastSegment() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bind("greedyNotLastSegment", String.class));
        assertTrue(ex.getMessage().contains("must be the last segment"), ex.getMessage());
    }

    @Test
    void methodPermissionAndDescriptionOverrideClassDefaults() throws Exception {
        RouteBinding binding = bind("annotated", String.class);
        assertEquals("method.perm", binding.permission);
        assertEquals("does a thing", binding.description);
        assertEquals("info <x>", binding.rawPattern);
    }

    @Test
    void optionalTail_bindsSenderAndOptionalParam() throws Exception {
        RouteBinding binding = bind("optionalTail", CommandSender.class, String.class);
        assertEquals(2, binding.params.size());
        assertTrue(binding.params.get(0).sender);
        assertTrue(binding.params.get(1).optional);
        // The class-level permission applies when the method declares none.
        assertEquals("class.perm", binding.permission);
    }

    @Test
    void findParamByName_returnsNullForUnknownAndSenderParams() throws Exception {
        RouteBinding binding = bind("optionalTail", CommandSender.class, String.class);
        assertEquals("a", RouteBinder.findParamByName(binding.params, "a").name);
        // sender params are excluded; unknown names return null.
        org.junit.jupiter.api.Assertions.assertNull(RouteBinder.findParamByName(binding.params, "sender"));
        org.junit.jupiter.api.Assertions.assertNull(RouteBinder.findParamByName(binding.params, "missing"));
    }
}
