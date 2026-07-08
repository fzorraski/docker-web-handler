package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateUserRequest;
import br.com.fzdevx.application.dto.UpdateUserRequest;
import br.com.fzdevx.application.dto.UserResponse;
import br.com.fzdevx.domain.exception.AccessDeniedException;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.persistence.JsonFileRoleRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManageUsersUseCaseTest {

    @TempDir Path tempDir;
    @Mock AuthSessionManager sessionManager;

    ManageUsersUseCase useCase;
    JsonFileUserRepository userRepository;
    JsonFileRoleRepository roleRepository;
    AuthorizationService authorizationService;
    CurrentUser actor;

    User superAdmin;
    User admin;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = new JsonFileUserRepository(tempDir.resolve("users.json").toString());
        roleRepository = new JsonFileRoleRepository(tempDir.resolve("roles.json").toString());
        BuiltInRoles.all().forEach(roleRepository::save);

        superAdmin = new User("root", PasswordHasher.hash("root-pw"), BuiltInRoles.SUPER_ADMIN_ID);
        admin = new User("admin", PasswordHasher.hash("admin-pw"), BuiltInRoles.ADMIN_ID);
        userRepository.save(superAdmin);
        userRepository.save(admin);

        authorizationService = new AuthorizationService();
        setField(authorizationService, "userRepository", userRepository);
        setField(authorizationService, "roleRepository", roleRepository);

        useCase = new ManageUsersUseCase();
        useCase.userRepository = userRepository;
        useCase.roleRepository = roleRepository;
        useCase.authorizationService = authorizationService;
        useCase.sessionManager = sessionManager;
        useCase.auditLogger = ManageSettingsUseCaseTest.NO_OP_AUDIT;
        actAs(superAdmin, EnumSet.allOf(Permission.class));
    }

    private void actAs(User user, Set<Permission> permissions) {
        actor = new CurrentUser();
        actor.set(user.getId(), user.getUsername(), permissions);
        useCase.currentUser = actor;
    }

    private void actAsAdmin() {
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.SYSTEM_CONFIG);
        actAs(admin, permissions);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private CreateUserRequest createRequest(String username, String password, String roleId) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setRoleIds(java.util.List.of(roleId));
        return request;
    }

    // ---- create ----

    @Test
    void create_validRequest_persistsHashedUser() {
        UserResponse created = useCase.create(createRequest("alice", "secret1", BuiltInRoles.OPERATOR_ID));

        assertEquals("alice", created.username());
        assertEquals(java.util.List.of("OPERATOR"), created.roleNames());
        User stored = userRepository.findByUsername("alice").orElseThrow();
        assertNotEquals("secret1", stored.getPasswordHash());
        assertTrue(PasswordHasher.verify("secret1", stored.getPasswordHash()));
    }

    @Test
    void create_duplicateUsernameCaseInsensitive_throws() {
        useCase.create(createRequest("alice", "secret1", BuiltInRoles.VIEWER_ID));
        assertThrows(DuplicateEntityException.class,
                () -> useCase.create(createRequest("ALICE", "secret2", BuiltInRoles.VIEWER_ID)));
    }

    @Test
    void create_invalidUsernameOrPassword_throws() {
        assertThrows(InvalidInputException.class,
                () -> useCase.create(createRequest("ab", "secret1", BuiltInRoles.VIEWER_ID)));
        assertThrows(InvalidInputException.class,
                () -> useCase.create(createRequest("with spaces", "secret1", BuiltInRoles.VIEWER_ID)));
        assertThrows(InvalidInputException.class,
                () -> useCase.create(createRequest("alice", "short", BuiltInRoles.VIEWER_ID)));
    }

    @Test
    void create_unknownRole_throws() {
        assertThrows(EntityNotFoundException.class,
                () -> useCase.create(createRequest("alice", "secret1", "no-such-role")));
    }

    @Test
    void create_adminAssigningSuperAdminRole_denied() {
        actAsAdmin();
        assertThrows(AccessDeniedException.class,
                () -> useCase.create(createRequest("evil", "secret1", BuiltInRoles.SUPER_ADMIN_ID)));
    }

    // ---- update ----

    @Test
    void update_changesRoleAndEnabled() {
        User viewer = new User("bob", "hash", BuiltInRoles.VIEWER_ID);
        userRepository.save(viewer);

        UpdateUserRequest request = new UpdateUserRequest();
        request.setRoleIds(java.util.List.of(BuiltInRoles.OPERATOR_ID));
        request.setEnabled(false);
        useCase.update(viewer.getId(), request);

        User updated = userRepository.findById(viewer.getId()).orElseThrow();
        assertEquals(java.util.List.of(BuiltInRoles.OPERATOR_ID), updated.getRoleIds());
        assertFalse(updated.isEnabled());
        verify(sessionManager).invalidateSessionsForUser(viewer.getId());
    }

    @Test
    void update_selfDisable_blocked() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setEnabled(false);
        assertThrows(InvalidInputException.class, () -> useCase.update(superAdmin.getId(), request));
    }

    @Test
    void update_adminTouchingSuperAdmin_denied() {
        actAsAdmin();
        UpdateUserRequest request = new UpdateUserRequest();
        request.setEnabled(false);
        assertThrows(AccessDeniedException.class, () -> useCase.update(superAdmin.getId(), request));
    }

    @Test
    void update_demotingLastSuperAdmin_blocked() {
        // superAdmin is the only enabled SYSTEM_CONFIG holder; add a second actor context
        User other = new User("other-root", "hash", BuiltInRoles.SUPER_ADMIN_ID);
        other.setEnabled(false);
        userRepository.save(other);

        UpdateUserRequest request = new UpdateUserRequest();
        request.setRoleIds(java.util.List.of(BuiltInRoles.ADMIN_ID));
        assertThrows(InvalidInputException.class, () -> useCase.update(superAdmin.getId(), request));
    }

    @Test
    void update_demotingSuperAdmin_allowedWhenAnotherExists() {
        User second = new User("root2", "hash", BuiltInRoles.SUPER_ADMIN_ID);
        userRepository.save(second);

        UpdateUserRequest request = new UpdateUserRequest();
        request.setRoleIds(java.util.List.of(BuiltInRoles.ADMIN_ID));
        useCase.update(second.getId(), request);

        assertEquals(java.util.List.of(BuiltInRoles.ADMIN_ID),
                userRepository.findById(second.getId()).orElseThrow().getRoleIds());
    }

    // ---- resetPassword ----

    @Test
    void resetPassword_updatesHashAndKillsSessions() {
        User bob = new User("bob", PasswordHasher.hash("old-pw"), BuiltInRoles.VIEWER_ID);
        userRepository.save(bob);

        useCase.resetPassword(bob.getId(), "new-secret");

        User updated = userRepository.findById(bob.getId()).orElseThrow();
        assertTrue(PasswordHasher.verify("new-secret", updated.getPasswordHash()));
        verify(sessionManager).invalidateSessionsForUser(bob.getId());
    }

    @Test
    void resetPassword_adminResettingSuperAdmin_denied() {
        actAsAdmin();
        assertThrows(AccessDeniedException.class,
                () -> useCase.resetPassword(superAdmin.getId(), "new-secret"));
    }

    // ---- delete ----

    @Test
    void delete_removesUserAndKillsSessions() {
        User bob = new User("bob", "hash", BuiltInRoles.VIEWER_ID);
        userRepository.save(bob);

        useCase.delete(bob.getId());

        assertTrue(userRepository.findById(bob.getId()).isEmpty());
        verify(sessionManager).invalidateSessionsForUser(bob.getId());
    }

    @Test
    void delete_self_blocked() {
        assertThrows(InvalidInputException.class, () -> useCase.delete(superAdmin.getId()));
    }

    @Test
    void delete_lastSuperAdmin_blocked() {
        // act as a plain admin cannot even touch; act as a hypothetical second
        // SYSTEM_CONFIG holder identity whose user record was already removed
        actAs(new User("ghost", "hash", BuiltInRoles.SUPER_ADMIN_ID), EnumSet.allOf(Permission.class));
        assertThrows(InvalidInputException.class, () -> useCase.delete(superAdmin.getId()));
    }

    @Test
    void delete_adminDeletingSuperAdmin_denied() {
        actAsAdmin();
        assertThrows(AccessDeniedException.class, () -> useCase.delete(superAdmin.getId()));
    }

    @Test
    void delete_customRoleWithSystemConfig_alsoProtectedByHierarchy() {
        Role customSuper = new Role("CustomSuper", null, EnumSet.of(Permission.SYSTEM_CONFIG));
        roleRepository.save(customSuper);
        User special = new User("special", "hash", customSuper.getId());
        userRepository.save(special);

        actAsAdmin();
        assertThrows(AccessDeniedException.class, () -> useCase.delete(special.getId()));
    }

    // ---- list ----

    @Test
    void list_neverExposesPasswordHashes() {
        var users = useCase.list();
        assertEquals(2, users.size());
        assertTrue(users.stream().anyMatch(u -> u.roleNames().contains("SUPER_ADMIN")));
    }
}
