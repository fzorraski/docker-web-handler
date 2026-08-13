package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.dto.AuditScope;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Single implementation of the tenant (squad) isolation rules, used by every
 * controller and use case that lists, guards or creates tenant-scoped
 * resources. A resource is visible when it has no tenant, when the actor is a
 * member of its tenant, when it is shared with one of the actor's tenants, or
 * when the actor holds TENANTS_VIEW_ALL. Outside a request scope
 * (scheduler/expiration workers) and with RBAC off, everything is visible.
 */
@ApplicationScoped
public class TenantVisibility {

    @Inject
    CurrentUser currentUser;

    @Inject
    AuthorizationService authorizationService;

    /** True when tenant filtering does not apply to the current caller. */
    public boolean bypass() {
        try {
            return !currentUser.isRbacActive()
                    || currentUser.hasPermission(Permission.TENANTS_VIEW_ALL);
        } catch (ContextNotActiveException e) {
            return true;
        }
    }

    /**
     * Which audit entries the caller may read: everything for a cross-tenant
     * reader, otherwise only their own tenants' entries.
     *
     * <p>Not expressible through {@link #canSee}: that method answers true for
     * a null tenant (an untenanted resource is shared), while an untenanted
     * audit entry records a system or super-admin action that a tenant-scoped
     * reader must not see. See {@link AuditScope}.</p>
     */
    public AuditScope auditScope() {
        // SYSTEM_CONFIG counts as cross-tenant reach here, as it does in
        // ManageUsersUseCase and ManageTenantsUseCase: a super admin whose role
        // happens not to carry TENANTS_VIEW_ALL would otherwise get an empty
        // trail and an empty action dropdown with nothing explaining why
        if (bypass() || currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            return AuditScope.unrestricted();
        }
        // bypass() returning false guarantees an active request scope
        return AuditScope.of(currentUser.getTenantIds());
    }

    public boolean canSee(String tenantId) {
        return canSee(tenantId, null);
    }

    public boolean canSee(String tenantId, List<String> sharedWithTenants) {
        if (tenantId == null || tenantId.isBlank() || bypass()) {
            return true;
        }
        Set<String> mine = currentUser.getTenantIds();
        if (mine.contains(tenantId)) {
            return true;
        }
        return sharedWithTenants != null && sharedWithTenants.stream().anyMatch(mine::contains);
    }

    /** Returns the visible subset as a new list; input elements are never mutated. */
    public <T> List<T> visible(List<T> items, Function<T, String> tenantOf) {
        return visible(items, tenantOf, item -> null);
    }

    public <T> List<T> visible(List<T> items, Function<T, String> tenantOf,
                               Function<T, List<String>> sharedOf) {
        if (bypass()) {
            return items;
        }
        return items.stream()
                .filter(item -> canSee(tenantOf.apply(item), sharedOf.apply(item)))
                .toList();
    }

    /** Hidden resources are reported as nonexistent so they cannot be probed. */
    public void requireVisible(String tenantId) {
        requireVisible(tenantId, null);
    }

    public void requireVisible(String tenantId, List<String> sharedWithTenants) {
        if (!canSee(tenantId, sharedWithTenants)) {
            throw new EntityNotFoundException("Resource not found.");
        }
    }

    /**
     * Resolves the tenant to stamp on a resource being created. A requested
     * tenant must be one of the actor's memberships (any existing tenant for
     * TENANTS_VIEW_ALL holders); with no request, defaults to the actor's
     * first membership, or no tenant for tenant-less users.
     */
    public String resolveCreationTenant(String requestedTenantId) {
        return resolveCreationTenant(requestedTenantId, false);
    }

    /**
     * As {@link #resolveCreationTenant(String)}, but {@code explicitNone}
     * distinguishes "the caller deliberately asked for an untenanted resource"
     * from "the caller said nothing about tenants".
     *
     * <p>The distinction matters because the default for a silent request is
     * the actor's first membership: without it, a TENANTS_VIEW_ALL holder who
     * also belongs to a tenant would pick "visible to everyone" in the UI and
     * silently get their own tenant stamped instead. Only cross-tenant actors
     * may create an untenanted resource, mirroring who is offered the choice.</p>
     */
    public String resolveCreationTenant(String requestedTenantId, boolean explicitNone) {
        Set<String> mine;
        boolean rbac;
        try {
            rbac = currentUser.isRbacActive();
            mine = currentUser.getTenantIds();
        } catch (ContextNotActiveException e) {
            // scheduler/worker threads: tenant comes pre-resolved from the persisted config
            return explicitNone ? null : normalize(requestedTenantId);
        }
        String requested = normalize(requestedTenantId);
        if (!rbac) {
            return null;
        }
        if (explicitNone) {
            if (!currentUser.hasPermission(Permission.TENANTS_VIEW_ALL)) {
                throw new InvalidInputException("Invalid tenant.");
            }
            return null;
        }
        if (requested == null) {
            return mine.isEmpty() ? null : mine.iterator().next();
        }
        if (mine.contains(requested)) {
            return requested;
        }
        if (currentUser.hasPermission(Permission.TENANTS_VIEW_ALL)
                && authorizationService.tenantById(requested).isPresent()) {
            return requested;
        }
        throw new InvalidInputException("Invalid tenant.");
    }

    private static String normalize(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : tenantId;
    }
}
