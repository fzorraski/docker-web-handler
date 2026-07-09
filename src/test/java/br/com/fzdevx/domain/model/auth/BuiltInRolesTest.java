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
    void onlySuperAdmin_holdsSystemConfig() {
        assertTrue(BuiltInRoles.superAdmin().hasPermission(Permission.SYSTEM_CONFIG));
        assertFalse(BuiltInRoles.admin().hasPermission(Permission.SYSTEM_CONFIG));
        assertFalse(BuiltInRoles.operator().hasPermission(Permission.SYSTEM_CONFIG));
        assertFalse(BuiltInRoles.viewer().hasPermission(Permission.SYSTEM_CONFIG));
    }
}
