package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.auth.Permission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture test: destroying a dump or a snapshot needs DATABASE_DELETE,
 * not DATABASE_OPERATE. The permission catalog advertises exactly that
 * ("deletion requires DATABASE_DELETE"), and a role meant to restore and
 * snapshot must not be able to wipe the backups it restores from.
 *
 * <p>Covers the bulk and cleanup-by-idle paths too - they delete just as
 * permanently as the single-item endpoint.</p>
 */
class DumpDeletionPermissionTest {

    @Test
    void everyDumpAndSnapshotDeletionRequiresDatabaseDelete() {
        List<String> violations = new ArrayList<>();
        checkDestructiveMethods(DatabaseDumpController.class, violations);
        checkDestructiveMethods(SnapshotController.class, violations);

        assertTrue(violations.isEmpty(),
                "Destructive dump/snapshot endpoints must require DATABASE_DELETE: " + violations);
    }

    private static void checkDestructiveMethods(Class<?> controller, List<String> violations) {
        for (Method method : controller.getDeclaredMethods()) {
            if (!isDestructive(method)) {
                continue;
            }
            RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
            if (annotation == null) {
                violations.add(controller.getSimpleName() + "." + method.getName() + " (no @RequiresPermission)");
                continue;
            }
            boolean requiresDelete = List.of(annotation.value()).contains(Permission.DATABASE_DELETE);
            if (!requiresDelete) {
                violations.add(controller.getSimpleName() + "." + method.getName()
                        + " requires " + List.of(annotation.value()));
            }
        }
    }

    /** A DELETE endpoint, or the cleanup-by-idle POST that removes old entries. */
    private static boolean isDestructive(Method method) {
        return method.isAnnotationPresent(jakarta.ws.rs.DELETE.class)
                || method.getName().toLowerCase().startsWith("cleanup");
    }
}
