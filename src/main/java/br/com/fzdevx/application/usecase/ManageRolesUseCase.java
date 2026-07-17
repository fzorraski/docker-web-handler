package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateRoleRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.AccessDeniedException;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class ManageRolesUseCase {

    @Inject
    RoleRepository roleRepository;

    @Inject
    UserRepository userRepository;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    CurrentUser currentUser;

    @Inject
    AuditLogger auditLogger;

    public List<Role> list() {
        return roleRepository.findAll();
    }

    public Role create(CreateRoleRequest request) {
        guardGlobalAdmin();
        String name = validateName(request.getName());
        Set<Permission> permissions = parsePermissions(request.getPermissions());
        guardSystemConfig(permissions);
        if (roleRepository.findByName(name).isPresent()) {
            throw new DuplicateEntityException("A role named '" + name + "' already exists.");
        }

        Role role = new Role(name, trimmedDescription(request), permissions);
        roleRepository.save(role);
        authorizationService.invalidateCache();
        auditLogger.log("ROLE_CREATE", name, permissions.size() + " permission(s)");
        return role;
    }

    public Role update(String id, CreateRoleRequest request) {
        guardGlobalAdmin();
        Role role = requireRole(id);
        if (role.isBuiltIn()) {
            throw new InvalidInputException("Built-in roles cannot be modified.");
        }
        guardSystemConfig(role.getPermissions());

        String name = validateName(request.getName());
        Set<Permission> permissions = parsePermissions(request.getPermissions());
        guardSystemConfig(permissions);
        roleRepository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateEntityException("A role named '" + name + "' already exists.");
                });

        String description = trimmedDescription(request);
        Role[] result = new Role[1];
        boolean found = roleRepository.update(id, stored -> {
            stored.setName(name);
            stored.setDescription(description);
            stored.setPermissions(permissions);
            result[0] = stored;
        });
        if (!found) {
            throw new EntityNotFoundException("Role not found.");
        }
        authorizationService.invalidateCache();
        auditLogger.log("ROLE_UPDATE", name, permissions.size() + " permission(s)");
        return result[0];
    }

    public void delete(String id) {
        guardGlobalAdmin();
        Role role = requireRole(id);
        if (role.isBuiltIn()) {
            throw new InvalidInputException("Built-in roles cannot be deleted.");
        }
        guardSystemConfig(role.getPermissions());
        boolean inUse = userRepository.findAll().stream()
                .anyMatch(user -> user.hasRole(role.getId()));
        if (inUse) {
            throw new InvalidInputException(
                    "Role '" + role.getName() + "' is assigned to one or more users and cannot be deleted.");
        }

        roleRepository.delete(role.getId());
        authorizationService.invalidateCache();
        auditLogger.log("ROLE_DELETE", role.getName(), null);
    }

    private Role requireRole(String id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Role not found."));
    }

    private String validateName(String name) {
        String trimmed = name != null ? name.trim() : "";
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new InvalidInputException("Role name must be 2-50 characters long.");
        }
        return trimmed;
    }

    private String trimmedDescription(CreateRoleRequest request) {
        return request.getDescription() != null ? request.getDescription().trim() : null;
    }

    private Set<Permission> parsePermissions(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new InvalidInputException("A role needs at least one permission.");
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String name : names) {
            try {
                permissions.add(Permission.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException("Unknown permission: " + name);
            }
        }
        return permissions;
    }

    /** Roles carrying SYSTEM_CONFIG may only be touched by holders of SYSTEM_CONFIG. */
    private void guardSystemConfig(Set<Permission> permissions) {
        if (permissions.contains(Permission.SYSTEM_CONFIG)
                && !currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            throw new AccessDeniedException(
                    "Only a super admin can manage roles that grant system configuration.");
        }
    }

    /**
     * Tenant-scoped admins (USERS_MANAGE without cross-tenant reach) assign roles
     * but never define them - editing a role they hold would let them grant
     * themselves TENANTS_VIEW_ALL and escape their tenant.
     */
    private void guardGlobalAdmin() {
        if (currentUser.isRbacActive()
                && !currentUser.hasPermission(Permission.TENANTS_VIEW_ALL)
                && !currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            throw new AccessDeniedException("Only a global admin can manage roles.");
        }
    }
}
