package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateTenantRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.AccessDeniedException;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ManageTenantsUseCase {

    @Inject
    TenantRepository tenantRepository;

    @Inject
    UserRepository userRepository;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    AuditLogger auditLogger;

    @Inject
    CurrentUser currentUser;

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @Inject
    DatabaseService databaseService;

    public List<Tenant> list() {
        return tenantRepository.findAll();
    }

    /** Full tenant management (CRUD + member counts) needs cross-tenant reach. */
    public void guardGlobalAdmin() {
        if (currentUser.isRbacActive()
                && !currentUser.hasPermission(Permission.TENANTS_VIEW_ALL)
                && !currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            throw new AccessDeniedException("Only a global admin can manage tenants.");
        }
    }

    public long memberCount(String tenantId) {
        return userRepository.findAll().stream()
                .filter(user -> user.getTenantIds().contains(tenantId))
                .count();
    }

    /** All member counts in one pass - the JSON repo re-reads the file per query. */
    public java.util.Map<String, Long> memberCounts() {
        return userRepository.findAll().stream()
                .flatMap(user -> user.getTenantIds().stream())
                .collect(java.util.stream.Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        java.util.stream.Collectors.counting()));
    }

    public Tenant create(CreateTenantRequest request) {
        guardGlobalAdmin();
        String name = validateName(request.getName());
        if (tenantRepository.findByName(name).isPresent()) {
            throw new DuplicateEntityException("A tenant named '" + name + "' already exists.");
        }

        Tenant tenant = new Tenant(name, trimmedDescription(request));
        tenant.setEnabledRepositories(validateEntitlementList(
                request.getEnabledRepositories(), knownRepositories(), "repository"));
        tenant.setEnabledDatabases(validateEntitlementList(
                request.getEnabledDatabases(), knownDatabases(), "database connection"));
        tenantRepository.save(tenant);
        authorizationService.invalidateCache();
        auditLogger.log("TENANT_CREATE", name, null);
        return tenant;
    }

    public Tenant update(String id, CreateTenantRequest request) {
        guardGlobalAdmin();
        requireTenant(id);
        String name = validateName(request.getName());
        tenantRepository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateEntityException("A tenant named '" + name + "' already exists.");
                });
        String description = trimmedDescription(request);
        List<String> enabledRepositories = validateEntitlementList(
                request.getEnabledRepositories(), knownRepositories(), "repository");
        List<String> enabledDatabases = validateEntitlementList(
                request.getEnabledDatabases(), knownDatabases(), "database connection");

        Tenant[] result = new Tenant[1];
        boolean found = tenantRepository.update(id, tenant -> {
            tenant.setName(name);
            tenant.setDescription(description);
            tenant.setEnabledRepositories(enabledRepositories);
            tenant.setEnabledDatabases(enabledDatabases);
            tenant.setUpdatedAt(Instant.now());
            result[0] = tenant;
        });
        if (!found) {
            throw new EntityNotFoundException("Tenant not found.");
        }
        authorizationService.invalidateCache();
        auditLogger.log("TENANT_UPDATE", name, entitlementDetail(enabledRepositories, enabledDatabases));
        return result[0];
    }

    /** Global option lists for the tenant entitlement editor in the admin UI. */
    public Map<String, List<String>> entitlementOptions() {
        guardGlobalAdmin();
        return Map.of("repositories", knownRepositories(),
                "databases", knownDatabases());
    }

    private List<String> knownRepositories() {
        return allowedRepositoryResolver.getAllowed();
    }

    private List<String> knownDatabases() {
        return allowedRepositoryResolver.getAllowed().stream()
                .filter(databaseService::hasDatabaseConfig)
                .toList();
    }

    /**
     * Null (= everything enabled) passes through; explicit lists are trimmed,
     * deduplicated and must reference globally configured entries.
     */
    private List<String> validateEntitlementList(List<String> requested, List<String> knownGlobal, String label) {
        if (requested == null) {
            return null;
        }
        List<String> cleaned = requested.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        for (String entry : cleaned) {
            if (!knownGlobal.contains(entry)) {
                throw new InvalidInputException("Unknown " + label + ": '" + entry + "'.");
            }
        }
        return cleaned;
    }

    private static String entitlementDetail(List<String> repositories, List<String> databases) {
        return "repos=" + (repositories == null ? "all" : repositories)
                + ", dbs=" + (databases == null ? "all" : databases);
    }

    public void delete(String id) {
        guardGlobalAdmin();
        Tenant tenant = requireTenant(id);
        long members = memberCount(tenant.getId());
        if (members > 0) {
            throw new InvalidInputException(
                    "Tenant '" + tenant.getName() + "' still has " + members
                            + " member(s) and cannot be deleted.");
        }

        tenantRepository.delete(tenant.getId());
        authorizationService.invalidateCache();
        auditLogger.log("TENANT_DELETE", tenant.getName(), null);
    }

    private Tenant requireTenant(String id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found."));
    }

    private String validateName(String name) {
        String trimmed = name != null ? name.trim() : "";
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new InvalidInputException("Tenant name must be 2-50 characters long.");
        }
        return trimmed;
    }

    private String trimmedDescription(CreateTenantRequest request) {
        return request.getDescription() != null ? request.getDescription().trim() : null;
    }
}
