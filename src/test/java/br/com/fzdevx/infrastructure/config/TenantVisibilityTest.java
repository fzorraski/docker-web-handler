package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantVisibilityTest {

    @Mock
    TenantRepository tenantRepository;

    private TenantVisibility rbacUser(Set<Permission> permissions, String... tenantIds) {
        CurrentUser user = new CurrentUser();
        user.set("u1", "alice", permissions, new java.util.LinkedHashSet<>(List.of(tenantIds)));
        return TestTenantVisibility.forUser(user, tenantRepository);
    }

    // ---- bypass ----

    @Test
    void bypass_whenRbacOff() {
        assertTrue(TestTenantVisibility.passthrough().bypass());
    }

    @Test
    void bypass_withTenantsViewAll() {
        assertTrue(rbacUser(Set.of(Permission.TENANTS_VIEW_ALL)).bypass());
    }

    @Test
    void noBypass_forRegularRbacUser() {
        assertFalse(rbacUser(Set.of(Permission.CONTAINERS_VIEW), "t1").bypass());
    }

    // ---- canSee ----

    @Test
    void canSee_nullTenant_alwaysVisible() {
        assertTrue(rbacUser(Set.of()).canSee(null));
        assertTrue(rbacUser(Set.of()).canSee("  "));
    }

    @Test
    void canSee_ownTenant() {
        assertTrue(rbacUser(Set.of(), "t1", "t2").canSee("t2"));
    }

    @Test
    void cannotSee_foreignTenant() {
        assertFalse(rbacUser(Set.of(), "t1").canSee("t2"));
    }

    @Test
    void cannotSee_foreignTenant_zeroMemberships() {
        assertFalse(rbacUser(Set.of()).canSee("t1"));
    }

    @Test
    void canSee_viaSharedList() {
        assertTrue(rbacUser(Set.of(), "t1").canSee("t2", List.of("t1", "t3")));
    }

    @Test
    void cannotSee_whenSharedListDoesNotIntersect() {
        assertFalse(rbacUser(Set.of(), "t1").canSee("t2", List.of("t3")));
    }

    @Test
    void canSee_everything_withViewAll() {
        assertTrue(rbacUser(Set.of(Permission.TENANTS_VIEW_ALL)).canSee("t9"));
    }

    // ---- visible ----

    @Test
    void visible_filtersForeignItems() {
        record Item(String tenant) {}
        List<Item> items = List.of(new Item(null), new Item("t1"), new Item("t2"));

        List<Item> visible = rbacUser(Set.of(), "t1").visible(items, Item::tenant);

        assertEquals(List.of(new Item(null), new Item("t1")), visible);
    }

    @Test
    void visible_returnsAll_whenBypassing() {
        record Item(String tenant) {}
        List<Item> items = List.of(new Item("t2"));
        assertEquals(items, TestTenantVisibility.passthrough().visible(items, Item::tenant));
    }

    // ---- requireVisible ----

    @Test
    void requireVisible_throwsNotFound_forHiddenResource() {
        assertThrows(EntityNotFoundException.class,
                () -> rbacUser(Set.of(), "t1").requireVisible("t2"));
    }

    @Test
    void requireVisible_passes_forSharedResource() {
        assertDoesNotThrow(() -> rbacUser(Set.of(), "t1").requireVisible("t2", List.of("t1")));
    }

    // ---- resolveCreationTenant ----

    @Test
    void resolveCreationTenant_rbacOff_returnsNull() {
        assertNull(TestTenantVisibility.passthrough().resolveCreationTenant("t1"));
    }

    @Test
    void resolveCreationTenant_defaultsToFirstMembership() {
        assertEquals("t1", rbacUser(Set.of(), "t1", "t2").resolveCreationTenant(null));
    }

    @Test
    void resolveCreationTenant_zeroMemberships_returnsNull() {
        assertNull(rbacUser(Set.of()).resolveCreationTenant(null));
    }

    @Test
    void resolveCreationTenant_acceptsOwnTenant() {
        assertEquals("t2", rbacUser(Set.of(), "t1", "t2").resolveCreationTenant("t2"));
    }

    @Test
    void resolveCreationTenant_rejectsForeignTenant() {
        assertThrows(InvalidInputException.class,
                () -> rbacUser(Set.of(), "t1").resolveCreationTenant("t2"));
    }

    @Test
    void resolveCreationTenant_viewAllMayPickAnyExistingTenant() {
        when(tenantRepository.findById("t9")).thenReturn(Optional.of(new Tenant("Nine", null)));
        assertEquals("t9", rbacUser(Set.of(Permission.TENANTS_VIEW_ALL)).resolveCreationTenant("t9"));
    }

    @Test
    void resolveCreationTenant_viewAllRejectsUnknownTenant() {
        when(tenantRepository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(InvalidInputException.class,
                () -> rbacUser(Set.of(Permission.TENANTS_VIEW_ALL)).resolveCreationTenant("nope"));
    }
}
