package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.domain.model.auth.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves user identity and permissions from an in-memory snapshot of the
 * user/role repositories. The JSON file repositories re-read their file on
 * every query, which is too slow for per-request permission checks, so this
 * service caches a snapshot and management use cases call
 * {@link #invalidateCache()} after every write - role edits and user
 * disable/delete take effect immediately without re-login.
 */
@ApplicationScoped
public class AuthorizationService {

    @Inject
    UserRepository userRepository;

    @Inject
    RoleRepository roleRepository;

    @Inject
    TenantRepository tenantRepository;

    public record ResolvedUser(String userId, String username, List<String> roleIds, List<String> roleNames,
                               List<String> tenantIds, List<String> tenantNames,
                               boolean enabled, Set<Permission> permissions) {

        public boolean hasPermission(Permission permission) {
            return permissions.contains(permission);
        }
    }

    private volatile Snapshot snapshot;

    private record Snapshot(Map<String, User> usersById, Map<String, Role> rolesById,
                            Map<String, Tenant> tenantsById) {
    }

    public Optional<ResolvedUser> resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Snapshot snap = loadSnapshot();
        User user = snap.usersById().get(userId);
        if (user == null) {
            return Optional.empty();
        }
        // permissions are the union of all assigned roles; deleted roles are skipped
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        List<String> roleNames = new ArrayList<>();
        for (String roleId : user.getRoleIds()) {
            Role role = snap.rolesById().get(roleId);
            if (role != null) {
                permissions.addAll(role.getPermissions());
                roleNames.add(role.getName());
            }
        }
        // memberships of deleted tenants are skipped, like deleted roles
        List<String> tenantIds = new ArrayList<>();
        List<String> tenantNames = new ArrayList<>();
        for (String tenantId : user.getTenantIds()) {
            Tenant tenant = snap.tenantsById().get(tenantId);
            if (tenant != null) {
                tenantIds.add(tenant.getId());
                tenantNames.add(tenant.getName());
            }
        }
        return Optional.of(new ResolvedUser(user.getId(), user.getUsername(),
                List.copyOf(user.getRoleIds()), List.copyOf(roleNames),
                List.copyOf(tenantIds), List.copyOf(tenantNames),
                user.isEnabled(), permissions.isEmpty() ? Set.of() : permissions));
    }

    public boolean hasPermission(String userId, Permission permission) {
        return resolve(userId)
                .filter(ResolvedUser::enabled)
                .map(u -> u.hasPermission(permission))
                .orElse(false);
    }

    /** Snapshot-backed tenant lookup for per-request checks (TenantVisibility). */
    public Optional<Tenant> tenantById(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(loadSnapshot().tenantsById().get(tenantId));
    }

    /** Snapshot-backed tenant listing for per-request checks (TenantEntitlements). */
    public java.util.Collection<Tenant> allTenants() {
        return loadSnapshot().tenantsById().values();
    }

    /**
     * Synchronised on the same monitor as the rebuild. Clearing the field
     * outside it loses the invalidation whenever it lands while another thread
     * is between reading the repositories and publishing its snapshot: that
     * thread then overwrites the null with data read before the change, and the
     * stale permissions stay cached until the next unrelated invalidation.
     */
    public synchronized void invalidateCache() {
        snapshot = null;
    }

    private Snapshot loadSnapshot() {
        Snapshot snap = snapshot;
        if (snap == null) {
            synchronized (this) {
                snap = snapshot;
                if (snap == null) {
                    snap = new Snapshot(
                            userRepository.findAll().stream()
                                    .collect(Collectors.toUnmodifiableMap(User::getId, Function.identity())),
                            roleRepository.findAll().stream()
                                    .collect(Collectors.toUnmodifiableMap(Role::getId, Function.identity())),
                            tenantRepository.findAll().stream()
                                    .collect(Collectors.toUnmodifiableMap(Tenant::getId, Function.identity())));
                    snapshot = snap;
                }
            }
        }
        return snap;
    }
}
