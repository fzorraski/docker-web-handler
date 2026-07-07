package br.com.fzdevx.interfaces.rest;

import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Architecture test: the authorization filter is default-allow for
 * unannotated resources, so every REST resource class must declare
 * {@link RequiresPermission} (at class level, or on every resource method).
 * Forgetting the annotation on a new controller fails this test instead of
 * silently shipping an unprotected endpoint.
 */
class RequiresPermissionCoverageTest {

    /** Deliberately unannotated: auth endpoints are open, CI uses API-key auth. */
    private static final Set<String> EXEMPT = Set.of("AuthController", "CiController");

    @Test
    void everyRestResourceDeclaresRequiredPermissions() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Class<?> clazz : restPackageClasses()) {
            if (!clazz.isAnnotationPresent(Path.class) || EXEMPT.contains(clazz.getSimpleName())) {
                continue;
            }
            if (clazz.isAnnotationPresent(RequiresPermission.class)) {
                continue;
            }
            boolean allMethodsAnnotated = Stream.of(clazz.getDeclaredMethods())
                    .filter(RequiresPermissionCoverageTest::isResourceMethod)
                    .allMatch(m -> m.isAnnotationPresent(RequiresPermission.class));
            if (!allMethodsAnnotated) {
                violations.add(clazz.getSimpleName());
            }
        }

        assertTrue(violations.isEmpty(),
                "REST resources missing @RequiresPermission (class-level or on every resource method): "
                        + violations);
    }

    private static boolean isResourceMethod(Method method) {
        return method.isAnnotationPresent(jakarta.ws.rs.GET.class)
                || method.isAnnotationPresent(jakarta.ws.rs.POST.class)
                || method.isAnnotationPresent(jakarta.ws.rs.PUT.class)
                || method.isAnnotationPresent(jakarta.ws.rs.DELETE.class)
                || method.isAnnotationPresent(jakarta.ws.rs.PATCH.class)
                || method.isAnnotationPresent(jakarta.ws.rs.HEAD.class)
                || method.isAnnotationPresent(jakarta.ws.rs.OPTIONS.class);
    }

    private static List<Class<?>> restPackageClasses() throws IOException, ClassNotFoundException {
        var classesDir = Paths.get("target/classes/br/com/fzdevx/interfaces/rest");
        if (!Files.isDirectory(classesDir)) {
            fail("Compiled classes not found at " + classesDir + " - run via Maven so target/classes exists.");
        }
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<java.nio.file.Path> files = Files.list(classesDir)) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = file.getFileName().toString().replace(".class", "");
                if (name.contains("$")) {
                    continue; // skip inner/anonymous classes
                }
                classes.add(Class.forName("br.com.fzdevx.interfaces.rest." + name));
            }
        }
        assertTrue(classes.size() >= 15, "Expected the REST package to contain controllers, found " + classes.size());
        return classes;
    }
}
