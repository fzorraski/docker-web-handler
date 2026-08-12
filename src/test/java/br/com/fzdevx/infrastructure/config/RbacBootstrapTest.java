package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import br.com.fzdevx.infrastructure.persistence.JsonFileRoleRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RbacBootstrapTest {

    @TempDir Path tempDir;

    RbacBootstrap bootstrap;
    JsonFileUserRepository userRepository;
    JsonFileRoleRepository roleRepository;

    @BeforeEach
    void setUp() {
        userRepository = new JsonFileUserRepository(tempDir.resolve("users.json").toString());
        roleRepository = new JsonFileRoleRepository(tempDir.resolve("roles.json").toString());
        bootstrap = new RbacBootstrap();
        bootstrap.rbacSettings = rbacSettings(true, "rbac");
        bootstrap.userRepository = userRepository;
        bootstrap.roleRepository = roleRepository;
        bootstrap.adminUsername = "admin";
        bootstrap.adminPassword = Optional.of("bootstrap-pw");
    }

    private static RbacSettings rbacSettings(boolean enabled, String mode) {
        RbacSettings settings = new RbacSettings();
        settings.authEnabled = enabled;
        settings.authMode = mode;
        return settings;
    }

    @Test
    void onStartup_upsertsBuiltInRolesAndSeedsSuperAdmin() {
        bootstrap.onStartup(null);

        assertEquals(4, roleRepository.findAll().size());
        assertTrue(roleRepository.findById(BuiltInRoles.SUPER_ADMIN_ID).isPresent());

        User admin = userRepository.findByUsername("admin").orElseThrow();
        assertEquals(java.util.List.of(BuiltInRoles.SUPER_ADMIN_ID), admin.getRoleIds());
        assertTrue(admin.isEnabled());
        assertTrue(PasswordHasher.verify("bootstrap-pw", admin.getPasswordHash()));
    }

    @Test
    void onStartup_doesNothingWhenRbacDisabled() {
        bootstrap.rbacSettings = rbacSettings(true, "password");
        bootstrap.onStartup(null);
        assertTrue(roleRepository.findAll().isEmpty());
        assertEquals(0, userRepository.count());

        bootstrap.rbacSettings = rbacSettings(false, "rbac");
        bootstrap.onStartup(null);
        assertTrue(roleRepository.findAll().isEmpty());
    }

    @Test
    void onStartup_skipsSeedWhenUsersAlreadyExist() {
        userRepository.save(new User("existing", "hash", BuiltInRoles.VIEWER_ID));
        bootstrap.onStartup(null);
        assertEquals(1, userRepository.count());
        assertTrue(userRepository.findByUsername("admin").isEmpty());
    }

    @Test
    void onStartup_skipsSeedWhenPasswordBlank() {
        bootstrap.adminPassword = Optional.of("  ");
        bootstrap.onStartup(null);
        assertEquals(0, userRepository.count());

        bootstrap.adminPassword = Optional.empty();
        bootstrap.onStartup(null);
        assertEquals(0, userRepository.count());
        // built-in roles are still upserted even without a seedable admin
        assertEquals(4, roleRepository.findAll().size());
    }

    @Test
    void onStartup_refreshesBuiltInRolePermissionsButKeepsCustomRoles() {
        Role stale = new Role(BuiltInRoles.VIEWER_ID, "VIEWER", "old",
                EnumSet.noneOf(Permission.class), true);
        roleRepository.save(stale);
        Role custom = new Role("Custom", null, EnumSet.of(Permission.LOGS_VIEW));
        roleRepository.save(custom);

        bootstrap.onStartup(null);

        Role viewer = roleRepository.findById(BuiltInRoles.VIEWER_ID).orElseThrow();
        assertTrue(viewer.hasPermission(Permission.CONTAINERS_VIEW), "built-in role must be refreshed");
        assertTrue(roleRepository.findById(custom.getId()).isPresent(), "custom roles must survive");
        assertEquals(5, roleRepository.findAll().size());
    }

    @Test
    void onStartup_leavesCustomizedBuiltInRoleAlone() {
        // a super admin retuned VIEWER: re-seeding would silently revert it
        Role edited = new Role(BuiltInRoles.VIEWER_ID, "VIEWER", "tuned",
                EnumSet.of(Permission.LOGS_VIEW), true);
        edited.setCustomized(true);
        roleRepository.save(edited);

        bootstrap.onStartup(null);

        Role viewer = roleRepository.findById(BuiltInRoles.VIEWER_ID).orElseThrow();
        assertEquals(EnumSet.of(Permission.LOGS_VIEW), viewer.getPermissions());
        assertFalse(viewer.hasPermission(Permission.CONTAINERS_VIEW), "customized role must not be re-seeded");
        assertTrue(viewer.isCustomized());
    }
}
