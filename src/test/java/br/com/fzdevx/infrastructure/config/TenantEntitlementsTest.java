package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantEntitlementsTest {

    @Mock
    TenantRepository tenantRepository;

    private TenantEntitlements rbacUser(Set<Permission> permissions, String... tenantIds) {
        CurrentUser user = new CurrentUser();
        user.set("u1", "alice", permissions, new java.util.LinkedHashSet<>(List.of(tenantIds)));
        return TestTenantEntitlements.forUser(user, tenantRepository);
    }

    private Tenant tenant(String id, List<String> repos, List<String> databases) {
        Tenant tenant = new Tenant("Tenant " + id, null);
        tenant.setId(id);
        tenant.setEnabledRepositories(repos);
        tenant.setEnabledDatabases(databases);
        return tenant;
    }

    private void tenantsExist(Tenant... tenants) {
        when(tenantRepository.findAll()).thenReturn(List.of(tenants));
    }

    // ---- bypass ----

    @Test
    void bypass_whenRbacOff() {
        assertTrue(TestTenantEntitlements.passthrough().bypass());
        assertTrue(TestTenantEntitlements.passthrough().repositoryAllowed("anything"));
    }

    @Test
    void bypass_withTenantsViewAll() {
        assertTrue(rbacUser(Set.of(Permission.TENANTS_VIEW_ALL)).bypass());
        assertTrue(rbacUser(Set.of(Permission.TENANTS_VIEW_ALL)).databaseAllowed("anything"));
    }

    @Test
    void noBypass_forRegularRbacUser() {
        assertFalse(rbacUser(Set.of(Permission.CONTAINERS_VIEW), "t1").bypass());
    }

    // ---- effective set semantics ----

    @Test
    void zeroTenants_unrestricted() {
        assertTrue(rbacUser(Set.of()).repositoryAllowed("repo-a"));
        assertTrue(rbacUser(Set.of()).databaseAllowed("repo-a"));
    }

    @Test
    void nullList_unrestricted() {
        tenantsExist(tenant("t1", null, null));
        assertTrue(rbacUser(Set.of(), "t1").repositoryAllowed("repo-a"));
        assertTrue(rbacUser(Set.of(), "t1").databaseAllowed("repo-a"));
    }

    @Test
    void explicitList_restricts() {
        tenantsExist(tenant("t1", List.of("repo-a"), List.of("repo-a")));
        TenantEntitlements entitlements = rbacUser(Set.of(), "t1");
        assertTrue(entitlements.repositoryAllowed("repo-a"));
        assertFalse(entitlements.repositoryAllowed("repo-b"));
    }

    @Test
    void emptyList_blocksEverything() {
        tenantsExist(tenant("t1", List.of(), List.of()));
        assertFalse(rbacUser(Set.of(), "t1").repositoryAllowed("repo-a"));
        assertFalse(rbacUser(Set.of(), "t1").databaseAllowed("repo-a"));
    }

    @Test
    void union_listPlusNull_unrestricted() {
        tenantsExist(tenant("t1", List.of("repo-a"), List.of()),
                tenant("t2", null, null));
        TenantEntitlements entitlements = rbacUser(Set.of(), "t1", "t2");
        assertTrue(entitlements.repositoryAllowed("repo-z"));
        assertTrue(entitlements.databaseAllowed("repo-z"));
    }

    @Test
    void union_listPlusList() {
        tenantsExist(tenant("t1", List.of("repo-a"), List.of("repo-a")),
                tenant("t2", List.of("repo-b"), List.of()));
        TenantEntitlements entitlements = rbacUser(Set.of(), "t1", "t2");
        assertTrue(entitlements.repositoryAllowed("repo-a"));
        assertTrue(entitlements.repositoryAllowed("repo-b"));
        assertFalse(entitlements.repositoryAllowed("repo-c"));
        assertTrue(entitlements.databaseAllowed("repo-a"));
        assertFalse(entitlements.databaseAllowed("repo-b"));
    }

    @Test
    void foreignTenantsDoNotContribute() {
        tenantsExist(tenant("t1", List.of("repo-a"), null),
                tenant("t9", List.of("repo-b"), null));
        assertFalse(rbacUser(Set.of(), "t1").repositoryAllowed("repo-b"));
    }

    @Test
    void dimensionsAreIndependent() {
        tenantsExist(tenant("t1", List.of("repo-a"), List.of("repo-b")));
        TenantEntitlements entitlements = rbacUser(Set.of(), "t1");
        assertTrue(entitlements.repositoryAllowed("repo-a"));
        assertFalse(entitlements.databaseAllowed("repo-a"));
        assertFalse(entitlements.repositoryAllowed("repo-b"));
        assertTrue(entitlements.databaseAllowed("repo-b"));
    }

    // ---- filter ----

    @Test
    void filter_returnsEntitledSubset() {
        tenantsExist(tenant("t1", List.of("repo-a", "stale-repo"), List.of("repo-b")));
        TenantEntitlements entitlements = rbacUser(Set.of(), "t1");
        List<String> global = List.of("repo-a", "repo-b", "repo-c");
        assertEquals(List.of("repo-a"), entitlements.filterRepositories(global));
        assertEquals(List.of("repo-b"), entitlements.filterDatabases(global));
    }

    @Test
    void filter_returnsInput_whenUnrestricted() {
        List<String> global = List.of("repo-a", "repo-b");
        assertEquals(global, TestTenantEntitlements.passthrough().filterRepositories(global));
        assertEquals(global, rbacUser(Set.of()).filterDatabases(global));
    }

    // ---- require ----

    @Test
    void require_throwsNotFound_forNonEntitledRepository() {
        tenantsExist(tenant("t1", List.of("repo-a"), List.of("repo-a")));
        TenantEntitlements entitlements = rbacUser(Set.of(), "t1");
        assertThrows(EntityNotFoundException.class, () -> entitlements.requireRepositoryAllowed("repo-b"));
        assertThrows(EntityNotFoundException.class, () -> entitlements.requireDatabaseAllowed("repo-b"));
        assertDoesNotThrow(() -> entitlements.requireRepositoryAllowed("repo-a"));
        assertDoesNotThrow(() -> entitlements.requireDatabaseAllowed("repo-a"));
    }
}
