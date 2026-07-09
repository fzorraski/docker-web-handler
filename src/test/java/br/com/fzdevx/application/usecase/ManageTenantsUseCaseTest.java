package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateTenantRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
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

    @InjectMocks
    ManageTenantsUseCase useCase;

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

    @Test
    void update_renames() {
        Tenant tenant = new Tenant("Old", null);
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));

        Tenant updated = useCase.update(tenant.getId(), request("New name"));

        assertEquals("New name", updated.getName());
        verify(tenantRepository).save(tenant);
        verify(authorizationService).invalidateCache();
    }

    @Test
    void update_unknownTenant_throws() {
        when(tenantRepository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> useCase.update("nope", request("Name")));
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
}
