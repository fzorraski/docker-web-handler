package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateTenantRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.domain.model.auth.TenantPalette;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManageTenantsUseCaseTest {

    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock AuthorizationService authorizationService;
    @Mock AuditLogger auditLogger;
    @Mock AllowedRepositoryResolver allowedRepositoryResolver;
    @Mock DatabaseService databaseService;

    @InjectMocks
    ManageTenantsUseCase useCase;

    @org.junit.jupiter.api.BeforeEach
    void actAsGlobalAdmin() {
        setActorPermissions(java.util.EnumSet.of(
                br.com.fzdevx.domain.model.auth.Permission.USERS_MANAGE,
                br.com.fzdevx.domain.model.auth.Permission.TENANTS_VIEW_ALL));
    }

    private void setActorPermissions(java.util.Set<br.com.fzdevx.domain.model.auth.Permission> permissions) {
        var actor = new br.com.fzdevx.infrastructure.config.CurrentUser();
        actor.set("actor-id", "actor", permissions);
        useCase.currentUser = actor;
    }

    private static CreateTenantRequest request(String name) {
        CreateTenantRequest request = new CreateTenantRequest();
        request.setName(name);
        return request;
    }

    @Test
    void create_savesAndInvalidatesCache() {
        Tenant created = useCase.create(request("Support"));

        assertEquals("Support", created.getName());
        verify(tenantRepository).save(created);
        verify(authorizationService).invalidateCache();
        verify(auditLogger).log(eq("TENANT_CREATE"), eq("Support"), any());
    }

    @Test
    void create_duplicateNameCaseInsensitive_throws() {
        when(tenantRepository.findByName("Support")).thenReturn(Optional.of(new Tenant("support", null)));

        assertThrows(DuplicateEntityException.class, () -> useCase.create(request("Support")));
    }

    @Test
    void create_nameTooShort_throws() {
        assertThrows(InvalidInputException.class, () -> useCase.create(request("x")));
    }

    /** The mock repo applies the mutator to the given tenant, like the real atomic update. */
    private void stubAtomicUpdate(Tenant tenant) {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(tenantRepository.update(eq(tenant.getId()), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<Tenant> mutator = invocation.getArgument(1);
            mutator.accept(tenant);
            return true;
        });
    }

    @Test
    void update_renames() {
        Tenant tenant = new Tenant("Old", null);
        stubAtomicUpdate(tenant);

        Tenant updated = useCase.update(tenant.getId(), request("New name"));

        assertEquals("New name", updated.getName());
        verify(tenantRepository).update(eq(tenant.getId()), any());
        verify(authorizationService).invalidateCache();
    }

    @Test
    void update_unknownTenant_throws() {
        when(tenantRepository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> useCase.update("nope", request("Name")));
    }

    // ---- badge colour ----

    @Test
    void create_withoutColor_picksOneFromThePalette() {
        Tenant created = useCase.create(request("Support"));

        assertTrue(TenantPalette.COLORS.contains(created.getColor()),
                "expected a palette colour, got " + created.getColor());
    }

    @Test
    void create_withoutColor_avoidsColorsOtherTenantsUse() {
        // every colour but the last is taken, so the new tenant must land on it
        List<Tenant> existing = new java.util.ArrayList<>();
        for (int i = 0; i < TenantPalette.COLORS.size() - 1; i++) {
            Tenant tenant = new Tenant("t" + i, null);
            tenant.setColor(TenantPalette.COLORS.get(i));
            existing.add(tenant);
        }
        when(tenantRepository.findAll()).thenReturn(existing);

        assertEquals(TenantPalette.COLORS.getLast(), useCase.create(request("Support")).getColor());
    }

    @Test
    void create_withExplicitColor_normalizesIt() {
        CreateTenantRequest request = request("Support");
        request.setColor(" #7c4dff ");

        assertEquals("#7C4DFF", useCase.create(request).getColor());
    }

    @Test
    void create_withInvalidColor_throws() {
        CreateTenantRequest request = request("Support");
        request.setColor("not-a-colour");

        assertThrows(InvalidInputException.class, () -> useCase.create(request));
    }

    @Test
    void update_withoutColor_keepsTheCurrentOne() {
        Tenant tenant = new Tenant("Old", null);
        tenant.setColor("#4CAF50");
        stubAtomicUpdate(tenant);

        assertEquals("#4CAF50", useCase.update(tenant.getId(), request("New name")).getColor());
    }

    @Test
    void update_withColor_replacesIt() {
        Tenant tenant = new Tenant("Old", null);
        tenant.setColor("#4CAF50");
        stubAtomicUpdate(tenant);
        CreateTenantRequest request = request("Old");
        request.setColor("#EC407A");

        assertEquals("#EC407A", useCase.update(tenant.getId(), request).getColor());
    }

    // ---- entitlements ----

    @Test
    void create_validEntitlements_arePersisted() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo-a", "repo-b"));
        when(databaseService.hasDatabaseConfig("repo-a")).thenReturn(true);
        CreateTenantRequest request = request("Support");
        request.setEnabledRepositories(List.of(" repo-a ", "repo-a", "repo-b"));
        request.setEnabledDatabases(List.of("repo-a"));

        Tenant created = useCase.create(request);

        assertEquals(List.of("repo-a", "repo-b"), created.getEnabledRepositories());
        assertEquals(List.of("repo-a"), created.getEnabledDatabases());
    }

    @Test
    void create_nullEntitlements_stayNull() {
        Tenant created = useCase.create(request("Support"));
        assertNull(created.getEnabledRepositories());
        assertNull(created.getEnabledDatabases());
    }

    @Test
    void create_emptyEntitlements_persistAsEmpty() {
        CreateTenantRequest request = request("Support");
        request.setEnabledRepositories(List.of());
        Tenant created = useCase.create(request);
        assertEquals(List.of(), created.getEnabledRepositories());
        assertNull(created.getEnabledDatabases());
    }

    @Test
    void create_unknownRepository_throws() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo-a"));
        CreateTenantRequest request = request("Support");
        request.setEnabledRepositories(List.of("repo-x"));
        assertThrows(InvalidInputException.class, () -> useCase.create(request));
    }

    @Test
    void create_repositoryWithoutDbConfig_isNotAValidDatabase() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo-a"));
        when(databaseService.hasDatabaseConfig("repo-a")).thenReturn(false);
        CreateTenantRequest request = request("Support");
        request.setEnabledDatabases(List.of("repo-a"));
        assertThrows(InvalidInputException.class, () -> useCase.create(request));
    }

    @Test
    void update_appliesEntitlementsThroughAtomicMutator() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo-a"));
        Tenant tenant = new Tenant("Support", null);
        stubAtomicUpdate(tenant);
        CreateTenantRequest request = request("Support");
        request.setEnabledRepositories(List.of("repo-a"));

        Tenant updated = useCase.update(tenant.getId(), request);

        assertEquals(List.of("repo-a"), updated.getEnabledRepositories());
        assertNull(updated.getEnabledDatabases());
    }

    @Test
    void entitlementOptions_exposesGlobalLists() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo-a", "repo-b"));
        when(databaseService.hasDatabaseConfig("repo-a")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("repo-b")).thenReturn(false);

        var options = useCase.entitlementOptions();

        assertEquals(List.of("repo-a", "repo-b"), options.get("repositories"));
        assertEquals(List.of("repo-a"), options.get("databases"));
    }

    @Test
    void entitlementOptions_deniedForTenantScopedAdmins() {
        setActorPermissions(java.util.EnumSet.of(br.com.fzdevx.domain.model.auth.Permission.USERS_MANAGE));
        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                useCase::entitlementOptions);
    }

    @Test
    void delete_blocked_whileUsersReferenceIt() {
        Tenant tenant = new Tenant("Support", null);
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        User member = new User("alice", "hash", "role-1");
        member.setTenantIds(List.of(tenant.getId()));
        when(userRepository.findAll()).thenReturn(List.of(member));

        assertThrows(InvalidInputException.class, () -> useCase.delete(tenant.getId()));
        verify(tenantRepository, never()).delete(any());
    }

    @Test
    void delete_removesUnreferencedTenant() {
        Tenant tenant = new Tenant("Support", null);
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(userRepository.findAll()).thenReturn(List.of());

        useCase.delete(tenant.getId());

        verify(tenantRepository).delete(tenant.getId());
        verify(authorizationService).invalidateCache();
    }

    @Test
    void tenantCrud_deniedForTenantScopedAdmins() {
        // USERS_MANAGE alone is not enough - tenant structure is global-admin territory
        setActorPermissions(java.util.EnumSet.of(br.com.fzdevx.domain.model.auth.Permission.USERS_MANAGE));

        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                () -> useCase.create(request("Sneaky")));
        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                () -> useCase.update("t1", request("Renamed")));
        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                () -> useCase.delete("t1"));
        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                useCase::guardGlobalAdmin);
        verify(tenantRepository, never()).save(any());
        verify(tenantRepository, never()).delete(any());
    }

    @Test
    void tenantCrud_allowedWithSystemConfig() {
        setActorPermissions(java.util.EnumSet.of(
                br.com.fzdevx.domain.model.auth.Permission.USERS_MANAGE,
                br.com.fzdevx.domain.model.auth.Permission.SYSTEM_CONFIG));

        useCase.create(request("Support"));
        verify(tenantRepository).save(any());
    }

    @Test
    void tenantCrud_deniedForBuiltInAdmin() {
        // the built-in ADMIN is tenant-scoped now: only a super admin creates
        // tenants or hands one to somebody
        setActorPermissions(br.com.fzdevx.domain.model.auth.BuiltInRoles.admin().getPermissions());

        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                useCase::guardGlobalAdmin);
        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                () -> useCase.create(request("New Squad")));
        verify(tenantRepository, never()).save(any());
    }
}
