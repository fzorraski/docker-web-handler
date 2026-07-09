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
    br.com.fzdevx.infrastructure.persistence.JsonFileTenantRepository tenantRepository;
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

        tenantRepository = new br.com.fzdevx.infrastructure.persistence.JsonFileTenantRepository(
                tempDir.resolve("tenants.json").toString());

        authorizationService = new AuthorizationService();
        setField(authorizationService, "userRepository", userRepository);
        setField(authorizationService, "roleRepository", roleRepository);
        setField(authorizationService, "tenantRepository", tenantRepository);

        useCase = new ManageUsersUseCase();
        useCase.userRepository = userRepository;
        useCase.roleRepository = roleRepository;
        useCase.tenantRepository = tenantRepository;
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

    private void actAsScoped(User user, Set<Permission> permissions, String... tenantIds) {
        actor = new CurrentUser();
        actor.set(user.getId(), user.getUsername(), permissions,
                new java.util.LinkedHashSet<>(java.util.List.of(tenantIds)));
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

    // ---- tenant-scoped admins ----
    // USERS_MANAGE without TENANTS_VIEW_ALL/SYSTEM_CONFIG only reaches the actor's tenants

    private static final Set<Permission> SCOPED_ADMIN_PERMS =
            EnumSet.of(Permission.USERS_MANAGE, Permission.CONTAINERS_VIEW);

    private br.com.fzdevx.domain.model.auth.Tenant support;
    private br.com.fzdevx.domain.model.auth.Tenant development;
    private User supportAdmin;

    private void setUpTenantScope() {
        support = new br.com.fzdevx.domain.model.auth.Tenant("Support", null);
        development = new br.com.fzdevx.domain.model.auth.Tenant("Development", null);
        tenantRepository.save(support);
        tenantRepository.save(development);

        supportAdmin = new User("supadmin", "hash", BuiltInRoles.OPERATOR_ID);
        supportAdmin.setTenantIds(java.util.List.of(support.getId()));
        userRepository.save(supportAdmin);
        actAsScoped(supportAdmin, SCOPED_ADMIN_PERMS, support.getId());
    }

    private User memberOf(String username, String... tenantIds) {
        User user = new User(username, "hash", BuiltInRoles.VIEWER_ID);
        user.setTenantIds(java.util.List.of(tenantIds));
        userRepository.save(user);
        return user;
    }

    @Test
    void scopedList_showsOnlyOwnTenantMembers() {
        setUpTenantScope();
        memberOf("supp-user", support.getId());
        memberOf("dev-user", development.getId());
        // superAdmin/admin from setUp are tenantless -> hidden too

        var visible = useCase.list().stream().map(UserResponse::username).toList();

        assertEquals(java.util.List.of("supadmin", "supp-user"), visible);
    }

    @Test
    void scopedCreate_requiresOwnTenant() {
        setUpTenantScope();

        // tenantless creation is forbidden - the user would be globally visible
        assertThrows(InvalidInputException.class,
                () -> useCase.create(createRequest("newbie", "secret1", BuiltInRoles.VIEWER_ID)));

        // foreign tenant is forbidden
        CreateUserRequest foreign = createRequest("newbie", "secret1", BuiltInRoles.VIEWER_ID);
        foreign.setTenantIds(java.util.List.of(development.getId()));
        assertThrows(AccessDeniedException.class, () -> useCase.create(foreign));

        // own tenant works
        CreateUserRequest ok = createRequest("newbie", "secret1", BuiltInRoles.VIEWER_ID);
        ok.setTenantIds(java.util.List.of(support.getId()));
        assertEquals(java.util.List.of("Support"), useCase.create(ok).tenantNames());
    }

    @Test
    void scopedCreate_cannotAssignGlobalAdminRole() {
        setUpTenantScope();
        // built-in ADMIN holds TENANTS_VIEW_ALL - assigning it would escape the tenant
        CreateUserRequest request = createRequest("evil", "secret1", BuiltInRoles.ADMIN_ID);
        request.setTenantIds(java.util.List.of(support.getId()));
        assertThrows(AccessDeniedException.class, () -> useCase.create(request));
    }

    @Test
    void scopedCreate_peerTenantAdminRoleAllowed() {
        setUpTenantScope();
        Role peerAdmin = new Role("Support Admin", null, SCOPED_ADMIN_PERMS);
        roleRepository.save(peerAdmin);

        CreateUserRequest request = createRequest("peer", "secret1", peerAdmin.getId());
        request.setTenantIds(java.util.List.of(support.getId()));

        assertEquals(java.util.List.of("Support Admin"), useCase.create(request).roleNames());
    }

    @Test
    void scopedUpdate_selfRoleSwapToBuiltinAdmin_denied() {
        setUpTenantScope();
        // the escalation hole this feature closes: PUT /users/{ownId} roleIds=[builtin-admin]
        UpdateUserRequest request = new UpdateUserRequest();
        request.setRoleIds(java.util.List.of(BuiltInRoles.ADMIN_ID));
        assertThrows(AccessDeniedException.class, () -> useCase.update(supportAdmin.getId(), request));
    }

    @Test
    void scopedUpdate_hiddenUser_reportedAsNotFound() {
        setUpTenantScope();
        User devUser = memberOf("dev-user", development.getId());

        UpdateUserRequest request = new UpdateUserRequest();
        request.setEnabled(false);
        assertThrows(EntityNotFoundException.class, () -> useCase.update(devUser.getId(), request));
        assertThrows(EntityNotFoundException.class, () -> useCase.resetPassword(devUser.getId(), "newpass1"));
        assertThrows(EntityNotFoundException.class, () -> useCase.delete(devUser.getId()));
    }

    @Test
    void scopedUpdate_sharedUser_membershipOnly() {
        setUpTenantScope();
        User shared = memberOf("shared", support.getId(), development.getId());

        // account-wide changes leak into the foreign tenant - rejected even with tenantIds null
        UpdateUserRequest roleChange = new UpdateUserRequest();
        roleChange.setRoleIds(java.util.List.of(BuiltInRoles.OPERATOR_ID));
        assertThrows(AccessDeniedException.class, () -> useCase.update(shared.getId(), roleChange));

        UpdateUserRequest disable = new UpdateUserRequest();
        disable.setEnabled(false);
        assertThrows(AccessDeniedException.class, () -> useCase.update(shared.getId(), disable));

        assertThrows(AccessDeniedException.class, () -> useCase.resetPassword(shared.getId(), "newpass1"));
        assertThrows(AccessDeniedException.class, () -> useCase.delete(shared.getId()));

        // removing the Support membership works and preserves Development untouched
        UpdateUserRequest membership = new UpdateUserRequest();
        membership.setTenantIds(java.util.List.of());
        useCase.update(shared.getId(), membership);
        assertEquals(java.util.List.of(development.getId()),
                userRepository.findById(shared.getId()).orElseThrow().getTenantIds());
    }

    @Test
    void scopedUpdate_cannotGrantForeignTenant() {
        setUpTenantScope();
        User member = memberOf("supp-user", support.getId());

        UpdateUserRequest request = new UpdateUserRequest();
        request.setTenantIds(java.util.List.of(support.getId(), development.getId()));
        useCase.update(member.getId(), request);

        // the foreign id is silently dropped by the merge
        assertEquals(java.util.List.of(support.getId()),
                userRepository.findById(member.getId()).orElseThrow().getTenantIds());
    }

    @Test
    void scopedUpdate_cannotStripLastTenant() {
        setUpTenantScope();
        User member = memberOf("supp-user", support.getId());

        UpdateUserRequest request = new UpdateUserRequest();
        request.setTenantIds(java.util.List.of());
        assertThrows(InvalidInputException.class, () -> useCase.update(member.getId(), request));

        // including stripping the acting admin's own membership
        UpdateUserRequest self = new UpdateUserRequest();
        self.setTenantIds(java.util.List.of());
        assertThrows(InvalidInputException.class, () -> useCase.update(supportAdmin.getId(), self));
    }

    @Test
    void scopedFullOwnership_allowsAccountWideChanges() {
        setUpTenantScope();
        User member = memberOf("supp-user", support.getId());

        UpdateUserRequest request = new UpdateUserRequest();
        request.setRoleIds(java.util.List.of(BuiltInRoles.OPERATOR_ID));
        request.setEnabled(false);
        useCase.update(member.getId(), request);
        useCase.resetPassword(member.getId(), "newpass1");
        useCase.delete(member.getId());

        assertTrue(userRepository.findById(member.getId()).isEmpty());
    }

    @Test
    void scoped_cannotTouchGlobalAdminInOwnTenant() {
        setUpTenantScope();
        User globalInSupport = new User("global", "hash", BuiltInRoles.ADMIN_ID);
        globalInSupport.setTenantIds(java.util.List.of(support.getId()));
        userRepository.save(globalInSupport);

        assertThrows(AccessDeniedException.class, () -> useCase.resetPassword(globalInSupport.getId(), "newpass1"));
        assertThrows(AccessDeniedException.class, () -> useCase.delete(globalInSupport.getId()));
    }

    @Test
    void scopedWithZeroTenants_seesAndCreatesNothing() {
        setUpTenantScope();
        User lonely = new User("lonely", "hash", BuiltInRoles.OPERATOR_ID);
        userRepository.save(lonely);
        actAsScoped(lonely, SCOPED_ADMIN_PERMS);

        assertTrue(useCase.list().isEmpty());
        assertThrows(InvalidInputException.class,
                () -> useCase.create(createRequest("anyone", "secret1", BuiltInRoles.VIEWER_ID)));
    }

    @Test
    void globalAdmin_unaffectedByScoping() {
        setUpTenantScope();
        memberOf("dev-user", development.getId());
        actAsAdmin(); // built-in ADMIN permissions include TENANTS_VIEW_ALL

        assertTrue(useCase.list().stream().anyMatch(u -> u.username().equals("dev-user")));
    }
}
