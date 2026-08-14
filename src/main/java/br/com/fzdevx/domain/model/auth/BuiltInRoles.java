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
        // admins administer the tenants they belong to, not the whole install:
        // creating tenants, defining roles and reaching another tenant's
        // resources stay with the super admin
        permissions.remove(Permission.TENANTS_VIEW_ALL);
        return builtIn(ADMIN_ID, "ADMIN",
                "Full operational access and user management within the assigned tenants.",
                permissions);
    }

    public static Role operator() {
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.SYSTEM_CONFIG);
        permissions.remove(Permission.USERS_MANAGE);
        permissions.remove(Permission.TENANTS_VIEW_ALL);
        // operators keep creator visibility (AUDIT_VIEW) but not the full
        // audit trail, which is an admin-and-above capability
        permissions.remove(Permission.AUDIT_LOG_VIEW);
        // operators restore and snapshot, but destroying ANY database, dump or
        // snapshot is an admin-and-above capability. DATABASE_DELETE_OWN stays:
        // restore-into-existing and delete are fenced by the deletion policy, and
        // without delete-own an operator could not even refresh a database their
        // own earlier restore created - the role's core daily workflow
        permissions.remove(Permission.DATABASE_DELETE);
        return builtIn(OPERATOR_ID, "OPERATOR",
                "Container, database, schedule, terminal and log operations. May delete or overwrite only "
                        + "databases they created themselves (no deletion of other databases, dumps or snapshots).",
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
