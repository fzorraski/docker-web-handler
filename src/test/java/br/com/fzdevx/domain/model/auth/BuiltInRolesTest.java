package br.com.fzdevx.domain.model.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInRolesTest {

    @Test
    void superAdminAndAdmin_seeAllTenants() {
        assertTrue(BuiltInRoles.superAdmin().hasPermission(Permission.TENANTS_VIEW_ALL));
        assertTrue(BuiltInRoles.admin().hasPermission(Permission.TENANTS_VIEW_ALL));
    }

    @Test
    void operatorAndViewer_areTenantBound() {
        assertFalse(BuiltInRoles.operator().hasPermission(Permission.TENANTS_VIEW_ALL));
        assertFalse(BuiltInRoles.viewer().hasPermission(Permission.TENANTS_VIEW_ALL));
    }

    @Test
    void auditLogView_isAdminAndAbove_creatorVisibilityIsBroader() {
        // full audit trail: admins and up only
        assertTrue(BuiltInRoles.superAdmin().hasPermission(Permission.AUDIT_LOG_VIEW));
        assertTrue(BuiltInRoles.admin().hasPermission(Permission.AUDIT_LOG_VIEW));
        assertFalse(BuiltInRoles.operator().hasPermission(Permission.AUDIT_LOG_VIEW));
        assertFalse(BuiltInRoles.viewer().hasPermission(Permission.AUDIT_LOG_VIEW));

        // creator visibility: operators keep it, viewers don't
        assertTrue(BuiltInRoles.admin().hasPermission(Permission.AUDIT_VIEW));
        assertTrue(BuiltInRoles.operator().hasPermission(Permission.AUDIT_VIEW));
        assertFalse(BuiltInRoles.viewer().hasPermission(Permission.AUDIT_VIEW));
    }

    @Test
    void databaseDelete_isAdminAndAbove_operatorsStillRestoreAndSnapshot() {
        assertTrue(BuiltInRoles.superAdmin().hasPermission(Permission.DATABASE_DELETE));
        assertTrue(BuiltInRoles.admin().hasPermission(Permission.DATABASE_DELETE));
        assertFalse(BuiltInRoles.operator().hasPermission(Permission.DATABASE_DELETE),
                "operators must not destroy databases, dumps or snapshots");
        assertFalse(BuiltInRoles.viewer().hasPermission(Permission.DATABASE_DELETE));

        // the rest of the database workflow stays with the operator
        assertTrue(BuiltInRoles.operator().hasPermission(Permission.DATABASE_OPERATE));
        assertTrue(BuiltInRoles.operator().hasPermission(Permission.DATABASE_UPLOAD));
    }

    @Test
    void onlySuperAdmin_holdsSystemConfig() {
        assertTrue(BuiltInRoles.superAdmin().hasPermission(Permission.SYSTEM_CONFIG));
        assertFalse(BuiltInRoles.admin().hasPermission(Permission.SYSTEM_CONFIG));
        assertFalse(BuiltInRoles.operator().hasPermission(Permission.SYSTEM_CONFIG));
        assertFalse(BuiltInRoles.viewer().hasPermission(Permission.SYSTEM_CONFIG));
    }
}
