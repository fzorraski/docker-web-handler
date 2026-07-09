package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Single implementation of the per-tenant resource entitlement rules: which of
 * the globally configured repositories and database connections the current
 * actor may use. Entitlements are stored on {@link Tenant}; a null list means
 * "everything enabled" and the actor's effective set is the union across all
 * of their tenants. Like {@link TenantVisibility}, RBAC off, TENANTS_VIEW_ALL
 * holders, tenant-less users and non-request contexts (scheduler/expiration
 * workers) are unrestricted.
 */
@ApplicationScoped
public class TenantEntitlements {

    @Inject
    CurrentUser currentUser;

    @Inject
    TenantRepository tenantRepository;

    /** True when entitlement filtering does not apply to the current caller. */
    public boolean bypass() {
        try {
            return !currentUser.isRbacActive()
                    || currentUser.hasPermission(Permission.TENANTS_VIEW_ALL);
        } catch (ContextNotActiveException e) {
            return true;
        }
    }

    public boolean repositoryAllowed(String repository) {
        Set<String> effective = effectiveSet(Tenant::getEnabledRepositories);
        return effective == null || effective.contains(repository);
    }

    public boolean databaseAllowed(String repository) {
        Set<String> effective = effectiveSet(Tenant::getEnabledDatabases);
        return effective == null || effective.contains(repository);
    }

    /** Returns the entitled subset as a new list; unrestricted callers get the input back. */
    public List<String> filterRepositories(List<String> repositories) {
        return filter(repositories, Tenant::getEnabledRepositories);
    }

    public List<String> filterDatabases(List<String> repositories) {
        return filter(repositories, Tenant::getEnabledDatabases);
    }

    /** Non-entitled repositories are reported as nonexistent so they cannot be probed. */
    public void requireRepositoryAllowed(String repository) {
        if (!repositoryAllowed(repository)) {
            throw new EntityNotFoundException("Resource not found.");
        }
    }

    public void requireDatabaseAllowed(String repository) {
        if (!databaseAllowed(repository)) {
            throw new EntityNotFoundException("Resource not found.");
        }
    }

    private List<String> filter(List<String> repositories, Function<Tenant, List<String>> extractor) {
        Set<String> effective = effectiveSet(extractor);
        if (effective == null) {
            return repositories;
        }
        return repositories.stream().filter(effective::contains).toList();
    }

    /**
     * The union of the given entitlement list across the actor's tenants, or
     * null when unrestricted (bypass, no tenants, or any tenant with a null
     * list). Reads the tenant store once per call so all checks in one request
     * see a consistent snapshot.
     */
    private Set<String> effectiveSet(Function<Tenant, List<String>> extractor) {
        if (bypass()) {
            return null;
        }
        Set<String> mine = currentUser.getTenantIds();
        if (mine.isEmpty()) {
            return null;
        }
        Set<String> union = new HashSet<>();
        for (Tenant tenant : tenantRepository.findAll()) {
            if (!mine.contains(tenant.getId())) {
                continue;
            }
            List<String> enabled = extractor.apply(tenant);
            if (enabled == null) {
                return null;
            }
            union.addAll(enabled);
        }
        return union;
    }
}
