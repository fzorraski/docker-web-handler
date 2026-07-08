package br.com.fzdevx.domain.model.auth;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class BuiltInRoles {

    public static final String SUPER_ADMIN_ID = "builtin-super-admin";
    public static final String ADMIN_ID = "builtin-admin";
    public static final String OPERATOR_ID = "builtin-operator";
    public static final String VIEWER_ID = "builtin-viewer";

    private BuiltInRoles() {
    }

    public static List<Role> all() {
        return List.of(superAdmin(), admin(), operator(), viewer());
    }

    public static Role superAdmin() {
        return builtIn(SUPER_ADMIN_ID, "SUPER_ADMIN",
                "Full access including system configuration.",
                EnumSet.allOf(Permission.class));
    }

    public static Role admin() {
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.SYSTEM_CONFIG);
        return builtIn(ADMIN_ID, "ADMIN",
                "Full operational access and user management.",
                permissions);
    }

    public static Role operator() {
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.SYSTEM_CONFIG);
        permissions.remove(Permission.USERS_MANAGE);
        return builtIn(OPERATOR_ID, "OPERATOR",
                "Container, database, schedule, terminal and log operations.",
                permissions);
    }

    public static Role viewer() {
        return builtIn(VIEWER_ID, "VIEWER",
                "Read-only access.",
                EnumSet.of(Permission.CONTAINERS_VIEW, Permission.IMAGES_VIEW,
                        Permission.DATABASE_VIEW, Permission.SCHEDULES_VIEW,
                        Permission.LOGS_VIEW));
    }

    private static Role builtIn(String id, String name, String description, Set<Permission> permissions) {
        return new Role(id, name, description, permissions, true);
    }
}
