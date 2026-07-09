package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateTenantRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class ManageTenantsUseCase {

    @Inject
    TenantRepository tenantRepository;

    @Inject
    UserRepository userRepository;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    AuditLogger auditLogger;

    public List<Tenant> list() {
        return tenantRepository.findAll();
    }

    public long memberCount(String tenantId) {
        return userRepository.findAll().stream()
                .filter(user -> user.getTenantIds().contains(tenantId))
                .count();
    }

    public Tenant create(CreateTenantRequest request) {
        String name = validateName(request.getName());
        if (tenantRepository.findByName(name).isPresent()) {
            throw new DuplicateEntityException("A tenant named '" + name + "' already exists.");
        }

        Tenant tenant = new Tenant(name, trimmedDescription(request));
        tenantRepository.save(tenant);
        authorizationService.invalidateCache();
        auditLogger.log("TENANT_CREATE", name, null);
        return tenant;
    }

    public Tenant update(String id, CreateTenantRequest request) {
        Tenant tenant = requireTenant(id);
        String name = validateName(request.getName());
        tenantRepository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateEntityException("A tenant named '" + name + "' already exists.");
                });

        tenant.setName(name);
        tenant.setDescription(trimmedDescription(request));
        tenant.setUpdatedAt(Instant.now());
        tenantRepository.save(tenant);
        authorizationService.invalidateCache();
        auditLogger.log("TENANT_UPDATE", name, null);
        return tenant;
    }

    public void delete(String id) {
        Tenant tenant = requireTenant(id);
        long members = memberCount(tenant.getId());
        if (members > 0) {
            throw new InvalidInputException(
                    "Tenant '" + tenant.getName() + "' still has " + members
                            + " member(s) and cannot be deleted.");
        }

        tenantRepository.delete(tenant.getId());
        authorizationService.invalidateCache();
        auditLogger.log("TENANT_DELETE", tenant.getName(), null);
    }

    private Tenant requireTenant(String id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found."));
    }

    private String validateName(String name) {
        String trimmed = name != null ? name.trim() : "";
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new InvalidInputException("Tenant name must be 2-50 characters long.");
        }
        return trimmed;
    }

    private String trimmedDescription(CreateTenantRequest request) {
        return request.getDescription() != null ? request.getDescription().trim() : null;
    }
}
