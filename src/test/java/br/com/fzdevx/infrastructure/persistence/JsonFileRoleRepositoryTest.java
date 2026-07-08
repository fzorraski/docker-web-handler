package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileRoleRepositoryTest {

    @TempDir Path tempDir;
    JsonFileRoleRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JsonFileRoleRepository(tempDir.resolve("roles.json").toString());
    }

    @Test
    void save_thenReload_roundTripsPermissionSet() {
        Role role = new Role("DBA", "Database operations only",
                EnumSet.of(Permission.DATABASE_VIEW, Permission.DATABASE_OPERATE, Permission.DATABASE_UPLOAD));
        repo.save(role);

        // fresh instance to force a file re-read (verifies JSON-B round-trip of Set<Permission>)
        JsonFileRoleRepository reloaded =
                new JsonFileRoleRepository(tempDir.resolve("roles.json").toString());
        Role found = reloaded.findById(role.getId()).orElseThrow();
        assertEquals("DBA", found.getName());
        assertEquals("Database operations only", found.getDescription());
        assertEquals(Set.of(Permission.DATABASE_VIEW, Permission.DATABASE_OPERATE, Permission.DATABASE_UPLOAD),
                found.getPermissions());
        assertFalse(found.isBuiltIn());
        assertTrue(found.hasPermission(Permission.DATABASE_VIEW));
        assertFalse(found.hasPermission(Permission.TERMINAL_ACCESS));
    }

    @Test
    void save_upsertsBuiltInRoleByFixedId() {
        repo.save(BuiltInRoles.viewer());
        repo.save(BuiltInRoles.viewer());
        assertEquals(1, repo.findAll().size());
        Role found = repo.findById(BuiltInRoles.VIEWER_ID).orElseThrow();
        assertTrue(found.isBuiltIn());
    }

    @Test
    void findByName_isCaseInsensitive() {
        repo.save(new Role("Deployer", null, EnumSet.of(Permission.CONTAINERS_RUN)));
        assertTrue(repo.findByName("deployer").isPresent());
        assertTrue(repo.findByName("DEPLOYER").isPresent());
        assertEquals(Optional.empty(), repo.findByName("other"));
        assertEquals(Optional.empty(), repo.findByName(null));
    }

    @Test
    void delete_removesRole() {
        Role role = new Role("Temp", null, EnumSet.noneOf(Permission.class));
        repo.save(role);
        repo.delete(role.getId());
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void builtInRoles_haveExpectedPermissionBoundaries() {
        assertEquals(EnumSet.allOf(Permission.class), BuiltInRoles.superAdmin().getPermissions());
        assertFalse(BuiltInRoles.admin().hasPermission(Permission.SYSTEM_CONFIG));
        assertTrue(BuiltInRoles.admin().hasPermission(Permission.USERS_MANAGE));
        assertFalse(BuiltInRoles.operator().hasPermission(Permission.USERS_MANAGE));
        assertFalse(BuiltInRoles.operator().hasPermission(Permission.SYSTEM_CONFIG));
        assertEquals(EnumSet.of(Permission.CONTAINERS_VIEW, Permission.IMAGES_VIEW,
                        Permission.DATABASE_VIEW, Permission.SCHEDULES_VIEW,
                        Permission.LOGS_VIEW),
                BuiltInRoles.viewer().getPermissions());
    }
}
