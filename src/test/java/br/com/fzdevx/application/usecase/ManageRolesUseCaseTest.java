package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateRoleRequest;
import br.com.fzdevx.domain.exception.AccessDeniedException;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.persistence.JsonFileRoleRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ManageRolesUseCaseTest {

    @TempDir Path tempDir;

    ManageRolesUseCase useCase;
    JsonFileRoleRepository roleRepository;
    JsonFileUserRepository userRepository;

    @BeforeEach
    void setUp() throws Exception {
        roleRepository = new JsonFileRoleRepository(tempDir.resolve("roles.json").toString());
        userRepository = new JsonFileUserRepository(tempDir.resolve("users.json").toString());
        BuiltInRoles.all().forEach(roleRepository::save);

        AuthorizationService authorizationService = new AuthorizationService();
        setField(authorizationService, "userRepository", userRepository);
        setField(authorizationService, "roleRepository", roleRepository);

        useCase = new ManageRolesUseCase();
        useCase.roleRepository = roleRepository;
        useCase.userRepository = userRepository;
        useCase.authorizationService = authorizationService;
        useCase.auditLogger = ManageSettingsUseCaseTest.NO_OP_AUDIT;
        actWithPermissions(EnumSet.allOf(Permission.class)); // super admin by default
    }

    private void actWithPermissions(Set<Permission> permissions) {
        CurrentUser actor = new CurrentUser();
        actor.set("actor-id", "actor", permissions);
        useCase.currentUser = actor;
    }

    private void actAsAdmin() {
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.SYSTEM_CONFIG);
        actWithPermissions(permissions);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private CreateRoleRequest roleRequest(String name, List<String> permissions) {
        CreateRoleRequest request = new CreateRoleRequest();
        request.setName(name);
        request.setPermissions(permissions);
        return request;
    }

    // ---- create ----

    @Test
    void create_customRole_persists() {
        Role role = useCase.create(roleRequest("DBA", List.of("DATABASE_VIEW", "DATABASE_OPERATE")));

        Role stored = roleRepository.findById(role.getId()).orElseThrow();
        assertEquals("DBA", stored.getName());
        assertFalse(stored.isBuiltIn());
        assertEquals(Set.of(Permission.DATABASE_VIEW, Permission.DATABASE_OPERATE), stored.getPermissions());
    }

    @Test
    void create_duplicateNameCaseInsensitive_throws() {
        useCase.create(roleRequest("DBA", List.of("DATABASE_VIEW")));
        assertThrows(DuplicateEntityException.class,
                () -> useCase.create(roleRequest("dba", List.of("DATABASE_VIEW"))));
    }

    @Test
    void create_builtInNameCollision_throws() {
        assertThrows(DuplicateEntityException.class,
                () -> useCase.create(roleRequest("ADMIN", List.of("DATABASE_VIEW"))));
    }

    @Test
    void create_unknownOrEmptyPermissions_throws() {
        assertThrows(InvalidInputException.class,
                () -> useCase.create(roleRequest("X1", List.of("NOT_A_PERMISSION"))));
        assertThrows(InvalidInputException.class,
                () -> useCase.create(roleRequest("X2", List.of())));
        assertThrows(InvalidInputException.class,
                () -> useCase.create(roleRequest("X3", null)));
    }

    @Test
    void create_roleWithSystemConfig_requiresSuperAdmin() {
        actAsAdmin();
        assertThrows(AccessDeniedException.class,
                () -> useCase.create(roleRequest("Sneaky", List.of("SYSTEM_CONFIG"))));

        actWithPermissions(EnumSet.allOf(Permission.class));
        assertNotNull(useCase.create(roleRequest("LegitSuper", List.of("SYSTEM_CONFIG"))));
    }

    // ---- update ----

    @Test
    void update_builtInRole_blocked() {
        assertThrows(InvalidInputException.class,
                () -> useCase.update(BuiltInRoles.VIEWER_ID, roleRequest("VIEWER2", List.of("LOGS_VIEW"))));
    }

    @Test
    void update_customRole_replacesNameAndPermissions() {
        Role role = useCase.create(roleRequest("DBA", List.of("DATABASE_VIEW")));

        useCase.update(role.getId(), roleRequest("DBA-Plus", List.of("DATABASE_VIEW", "DATABASE_UPLOAD")));

        Role updated = roleRepository.findById(role.getId()).orElseThrow();
        assertEquals("DBA-Plus", updated.getName());
        assertTrue(updated.hasPermission(Permission.DATABASE_UPLOAD));
    }

    @Test
    void update_nameCollisionWithOtherRole_throws() {
        useCase.create(roleRequest("RoleA", List.of("LOGS_VIEW")));
        Role roleB = useCase.create(roleRequest("RoleB", List.of("LOGS_VIEW")));

        assertThrows(DuplicateEntityException.class,
                () -> useCase.update(roleB.getId(), roleRequest("RoleA", List.of("LOGS_VIEW"))));
    }

    @Test
    void update_grantingSystemConfig_requiresSuperAdmin() {
        Role role = useCase.create(roleRequest("Plain", List.of("LOGS_VIEW")));
        actAsAdmin();
        assertThrows(AccessDeniedException.class,
                () -> useCase.update(role.getId(), roleRequest("Plain", List.of("SYSTEM_CONFIG"))));
    }

    // ---- delete ----

    @Test
    void delete_builtInRole_blocked() {
        assertThrows(InvalidInputException.class, () -> useCase.delete(BuiltInRoles.OPERATOR_ID));
    }

    @Test
    void delete_roleInUse_blocked() {
        Role role = useCase.create(roleRequest("DBA", List.of("DATABASE_VIEW")));
        userRepository.save(new User("bob", "hash", role.getId()));

        assertThrows(InvalidInputException.class, () -> useCase.delete(role.getId()));
    }

    @Test
    void delete_unusedCustomRole_succeeds() {
        Role role = useCase.create(roleRequest("Temp", List.of("LOGS_VIEW")));
        useCase.delete(role.getId());
        assertTrue(roleRepository.findById(role.getId()).isEmpty());
    }

    // ---- tenant-scoped admins assign roles but never define them ----

    private void actAsTenantScopedAdmin() {
        actWithPermissions(EnumSet.of(Permission.USERS_MANAGE, Permission.CONTAINERS_VIEW));
    }

    @Test
    void roleCrud_deniedForTenantScopedAdmins() {
        Role existing = useCase.create(roleRequest("Harmless", List.of("LOGS_VIEW")));

        actAsTenantScopedAdmin();
        // editing a role they hold could grant themselves TENANTS_VIEW_ALL
        assertThrows(AccessDeniedException.class,
                () -> useCase.create(roleRequest("Sneaky", List.of("TENANTS_VIEW_ALL"))));
        assertThrows(AccessDeniedException.class,
                () -> useCase.update(existing.getId(), roleRequest("Harmless", List.of("LOGS_VIEW", "TENANTS_VIEW_ALL"))));
        assertThrows(AccessDeniedException.class, () -> useCase.delete(existing.getId()));

        // reading stays available (the user form needs role names)
        assertFalse(useCase.list().isEmpty());
    }

    @Test
    void roleCrud_allowedForGlobalAdmins() {
        actAsAdmin(); // holds TENANTS_VIEW_ALL (all minus SYSTEM_CONFIG)
        Role role = useCase.create(roleRequest("Fine", List.of("LOGS_VIEW")));
        useCase.delete(role.getId());
    }
}
