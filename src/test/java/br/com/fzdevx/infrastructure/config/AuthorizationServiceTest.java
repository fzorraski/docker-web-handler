package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthorizationServiceTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock TenantRepository tenantRepository;

    @InjectMocks
    AuthorizationService service;

    User alice;

    @BeforeEach
    void setUp() {
        alice = new User("alice", "hash", BuiltInRoles.VIEWER_ID);
        when(userRepository.findAll()).thenReturn(List.of(alice));
        when(roleRepository.findAll()).thenReturn(List.of(BuiltInRoles.viewer()));
    }

    @Test
    void resolve_returnsUserWithRolePermissions() {
        var resolved = service.resolve(alice.getId()).orElseThrow();

        assertEquals("alice", resolved.username());
        assertEquals(java.util.List.of("VIEWER"), resolved.roleNames());
        assertTrue(resolved.enabled());
        assertEquals(Set.of(Permission.CONTAINERS_VIEW, Permission.IMAGES_VIEW,
                Permission.DATABASE_VIEW, Permission.SCHEDULES_VIEW,
                Permission.LOGS_VIEW), resolved.permissions());
    }

    @Test
    void resolve_unknownOrBlankUserId_returnsEmpty() {
        assertEquals(Optional.empty(), service.resolve("nope"));
        assertEquals(Optional.empty(), service.resolve(null));
        assertEquals(Optional.empty(), service.resolve(" "));
    }

    @Test
    void resolve_userWithMissingRole_hasNoPermissions() {
        alice.setRoleIds(java.util.List.of("deleted-role"));

        var resolved = service.resolve(alice.getId()).orElseThrow();

        assertTrue(resolved.permissions().isEmpty());
        assertTrue(resolved.roleNames().isEmpty());
    }

    @Test
    void resolve_multipleRoles_permissionsAreTheUnion() {
        when(roleRepository.findAll()).thenReturn(List.of(BuiltInRoles.viewer(), BuiltInRoles.admin()));
        alice.setRoleIds(java.util.List.of(BuiltInRoles.VIEWER_ID, BuiltInRoles.ADMIN_ID));
        service.invalidateCache();

        var resolved = service.resolve(alice.getId()).orElseThrow();

        assertEquals(java.util.List.of("VIEWER", "ADMIN"), resolved.roleNames());
        assertTrue(resolved.hasPermission(Permission.USERS_MANAGE), "union must include admin permissions");
        assertTrue(resolved.hasPermission(Permission.CONTAINERS_VIEW));
    }

    @Test
    void hasPermission_checksRoleAndEnabledFlag() {
        assertTrue(service.hasPermission(alice.getId(), Permission.CONTAINERS_VIEW));
        assertFalse(service.hasPermission(alice.getId(), Permission.CONTAINERS_RUN));

        alice.setEnabled(false);
        service.invalidateCache();
        assertFalse(service.hasPermission(alice.getId(), Permission.CONTAINERS_VIEW));
    }

    @Test
    void resolve_cachesRepositoriesUntilInvalidated() {
        service.resolve(alice.getId());
        service.resolve(alice.getId());
        service.hasPermission(alice.getId(), Permission.LOGS_VIEW);

        verify(userRepository, times(1)).findAll();
        verify(roleRepository, times(1)).findAll();

        service.invalidateCache();
        service.resolve(alice.getId());

        verify(userRepository, times(2)).findAll();
    }

    @Test
    void invalidateCache_picksUpRoleEdits() {
        assertFalse(service.hasPermission(alice.getId(), Permission.TERMINAL_ACCESS));

        Role updatedViewer = new Role(BuiltInRoles.VIEWER_ID, "VIEWER", null,
                Set.of(Permission.TERMINAL_ACCESS), true);
        when(roleRepository.findAll()).thenReturn(List.of(updatedViewer));
        service.invalidateCache();

        assertTrue(service.hasPermission(alice.getId(), Permission.TERMINAL_ACCESS));
    }
}
