package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;

/**
 * Test wiring helpers for {@link TenantEntitlements}, which has package-private
 * injected fields. Most controller/use-case tests want the legacy pass-through
 * behavior (RBAC off, nothing restricted).
 */
public final class TestTenantEntitlements {

    private TestTenantEntitlements() {
    }

    /** Legacy mode: RBAC inactive, no entitlement filtering anywhere. */
    public static TenantEntitlements passthrough() {
        return forUser(new CurrentUser(), null);
    }

    /** RBAC mode with the given (already populated) CurrentUser. */
    public static TenantEntitlements forUser(CurrentUser currentUser, TenantRepository tenantRepository) {
        TenantEntitlements entitlements = new TenantEntitlements();
        entitlements.currentUser = currentUser;
        entitlements.authorizationService = TestAuthorization.withTenants(tenantRepository);
        return entitlements;
    }
}
