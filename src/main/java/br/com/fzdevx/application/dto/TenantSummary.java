package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.auth.Tenant;

/** Minimal tenant view for selectors - visible to any authenticated user. */
public record TenantSummary(String id, String name) {

    public static TenantSummary of(Tenant tenant) {
        return new TenantSummary(tenant.getId(), tenant.getName());
    }
}
