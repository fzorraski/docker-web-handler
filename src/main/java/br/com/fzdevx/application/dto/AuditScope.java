package br.com.fzdevx.application.dto;

import java.util.Set;

/**
 * Which tenants' audit entries a reader may see. Unrestricted means the reader
 * has cross-tenant reach (TENANTS_VIEW_ALL, or RBAC disabled) and sees the
 * whole trail.
 *
 * <p>Note the deliberate difference from resource visibility: for a resource a
 * null tenant means "shared, visible to everyone", but an audit entry with no
 * tenant records an action that could not be attributed to one - a system job,
 * a super admin operation, a login before the identity is known. Those must NOT
 * reach a tenant-scoped reader, so a restricted scope matches only the listed
 * tenants and never a null one. Do not filter audit entries through
 * {@code TenantVisibility.canSee}, which answers true for a null tenant.</p>
 */
public record AuditScope(Set<String> tenantIds) {

    /** Compact constructor: the defensive copy must hold however it is built. */
    public AuditScope {
        tenantIds = tenantIds == null ? null : Set.copyOf(tenantIds);
    }

    private static final AuditScope UNRESTRICTED = new AuditScope(null);

    public static AuditScope unrestricted() {
        return UNRESTRICTED;
    }

    public static AuditScope of(Set<String> tenantIds) {
        return new AuditScope(tenantIds == null ? Set.of() : tenantIds);
    }

    public boolean isUnrestricted() {
        return tenantIds == null;
    }

    /** True when this entry's tenant is readable under this scope. */
    public boolean allows(String tenantId) {
        return isUnrestricted() || (tenantId != null && tenantIds.contains(tenantId));
    }
}
