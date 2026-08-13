package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.domain.model.auth.TenantPalette;

/** Minimal tenant view for selectors - visible to any authenticated user. */
public record TenantSummary(String id, String name, String color) {

    /**
     * The colour is resolved here rather than left null, so every screen that
     * renders a tenant badge gets a usable value without repeating the fallback.
     */
    public static TenantSummary of(Tenant tenant) {
        return new TenantSummary(tenant.getId(), tenant.getName(),
                TenantPalette.resolve(tenant.getColor(), tenant.getId()));
    }
}
