package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;

/**
 * Test wiring helpers for {@link TenantVisibility}, which has package-private
 * injected fields. Most controller tests want the legacy pass-through
 * behavior (RBAC off, everything visible).
 */
public final class TestTenantVisibility {

    private TestTenantVisibility() {
    }

    /** Legacy mode: RBAC inactive, no filtering anywhere. */
    public static TenantVisibility passthrough() {
        return forUser(new CurrentUser(), null);
    }

    /** RBAC mode with the given (already populated) CurrentUser. */
    public static TenantVisibility forUser(CurrentUser currentUser, TenantRepository tenantRepository) {
        TenantVisibility visibility = new TenantVisibility();
        visibility.currentUser = currentUser;
        visibility.authorizationService = TestAuthorization.withTenants(tenantRepository);
        return visibility;
    }
}
