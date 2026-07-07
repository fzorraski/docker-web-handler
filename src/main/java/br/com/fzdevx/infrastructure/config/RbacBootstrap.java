package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

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
    }

    private void upsertBuiltInRoles() {
        for (Role role : BuiltInRoles.all()) {
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
