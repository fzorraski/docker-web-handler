package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;

/**
 * Test wiring helper for {@link TenantSharing} (package-private injected
 * field). Most tests share nothing, and normalising an empty list never
 * reaches the repository - hence the no-repository variant.
 */
public final class TestTenantSharing {

    private TestTenantSharing() {
    }

    /** No tenant lookups possible: fine as long as nothing is shared. */
    public static TenantSharing withoutRepository() {
        return with(null);
    }

    public static TenantSharing with(TenantRepository tenantRepository) {
        TenantSharing sharing = new TenantSharing();
        sharing.tenantRepository = tenantRepository;
        return sharing;
    }
}
