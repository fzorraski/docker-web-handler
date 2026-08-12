package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Seeds RBAC storage on startup: upserts the built-in roles (so new catalog
 * permissions propagate to them) and creates the initial super admin from
 * config when no users exist yet.
 */
@ApplicationScoped
public class RbacBootstrap {

    @Inject
    RbacSettings rbacSettings;

    @Inject
    UserRepository userRepository;

    @Inject
    RoleRepository roleRepository;

    @ConfigProperty(name = "rbac.admin.username", defaultValue = "admin")
    String adminUsername;

    @ConfigProperty(name = "rbac.admin.password")
    Optional<String> adminPassword;

    void onStartup(@Observes StartupEvent event) {
        if (!rbacSettings.isRbacEnabled()) {
            return;
        }
        upsertBuiltInRoles();
        seedInitialAdmin();
        warnAboutTenantlessAdmins();
    }

    /**
     * An admin without cross-tenant reach administers their own tenants, so one
     * with no tenant at all sees no users and can create none. That is silent
     * from the inside - the Users tab is simply empty - and it is the default
     * state of every admin created before the ADMIN role became tenant-scoped,
     * so it is worth naming them at boot.
     */
    private void warnAboutTenantlessAdmins() {
        try {
            // roles are read once for the whole scan: findById re-reads the roles
            // file per call on the JSON backend, so a per-user lookup would be
            // O(users x roles) file reads at every boot
            Map<String, Role> rolesById = roleRepository.findAll().stream()
                    .collect(Collectors.toMap(Role::getId, Function.identity(), (a, b) -> a));
            List<String> stranded = userRepository.findAll().stream()
                    .filter(User::isEnabled)
                    .filter(user -> user.getTenantIds() == null || user.getTenantIds().isEmpty())
                    .filter(user -> {
                        Set<Permission> permissions = permissionsOf(user, rolesById);
                        return permissions.contains(Permission.USERS_MANAGE)
                                && !permissions.contains(Permission.TENANTS_VIEW_ALL)
                                && !permissions.contains(Permission.SYSTEM_CONFIG);
                    })
                    .map(User::getUsername)
                    .toList();
            if (!stranded.isEmpty()) {
                Log.warnf("These users can manage users but belong to no tenant, so they see none: %s. "
                        + "A super admin must assign their tenants.", String.join(", ", stranded));
            }
        } catch (RuntimeException e) {
            // a diagnostic must never keep the application from starting
            Log.debugf(e, "Could not check for tenantless admins.");
        }
    }

    private static Set<Permission> permissionsOf(User user, Map<String, Role> rolesById) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String roleId : user.getRoleIds()) {
            Role role = rolesById.get(roleId);
            if (role != null) {
                permissions.addAll(role.getPermissions());
            }
        }
        return permissions;
    }

    /**
     * Seeds the built-in roles and keeps them in sync with the permission
     * catalog, so a permission added in a new release reaches them without
     * manual work. A built-in role a super admin has edited is left untouched -
     * re-seeding it would silently revert that edit on every restart. Such a
     * role therefore does NOT pick up new catalog permissions; grant them in
     * the admin UI.
     */
    private void upsertBuiltInRoles() {
        for (Role role : BuiltInRoles.all()) {
            Optional<Role> stored = roleRepository.findById(role.getId());
            if (stored.isPresent() && stored.get().isCustomized()) {
                Log.debugf("Built-in role '%s' was customized - leaving it as configured.", role.getName());
                continue;
            }
            roleRepository.save(role);
        }
        Log.debug("Built-in RBAC roles upserted.");
    }

    private void seedInitialAdmin() {
        if (userRepository.count() > 0) {
            return;
        }
        if (adminPassword.isEmpty() || adminPassword.get().isBlank()) {
            Log.error("RBAC is enabled but no users exist and 'rbac.admin.password' is not set. "
                    + "Nobody can log in. Set RBAC_ADMIN_PASSWORD (or rbac.admin.password) and restart.");
            return;
        }
        if (adminUsername == null || adminUsername.isBlank()) {
            Log.error("RBAC bootstrap skipped: 'rbac.admin.username' is blank.");
            return;
        }
        User admin = new User(adminUsername.trim(),
                PasswordHasher.hash(adminPassword.get()),
                BuiltInRoles.SUPER_ADMIN_ID);
        userRepository.save(admin);
        Log.infof("Seeded initial RBAC super admin user '%s'.", admin.getUsername());
    }
}
