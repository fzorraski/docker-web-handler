package br.com.fzdevx.infrastructure.config;

/**
 * Single definition of the tenant an audit entry is stamped with: the same
 * tenant the acting user would stamp on a resource they create. Both audit
 * logger implementations must agree on this - audit trails may not depend on
 * the configured persistence backend (mirrors {@link AuditActor}).
 *
 * <p>Null means the action is not attributable to a tenant: a system or
 * scheduler action, a tenantless user (typically a super admin), or RBAC
 * disabled. Such entries are readable only with cross-tenant reach.</p>
 */
public final class AuditTenant {

    private AuditTenant() {
    }

    public static String resolve(TenantVisibility tenantVisibility) {
        try {
            // null: asking for the actor's own default. A non-null request can
            // throw InvalidInputException, which an audit write must never do.
            return tenantVisibility.resolveCreationTenant(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
