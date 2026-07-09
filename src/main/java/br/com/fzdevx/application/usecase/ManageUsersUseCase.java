package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateUserRequest;
import br.com.fzdevx.application.dto.UpdateUserRequest;
import br.com.fzdevx.application.dto.UserResponse;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.AccessDeniedException;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class ManageUsersUseCase {

    static final int MIN_PASSWORD_LENGTH = 6;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{3,50}$");

    @Inject
    UserRepository userRepository;

    @Inject
    RoleRepository roleRepository;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    AuthSessionManager sessionManager;

    @Inject
    CurrentUser currentUser;

    @Inject
    AuditLogger auditLogger;

    public List<UserResponse> list() {
        Map<String, Role> rolesById = rolesById();
        Map<String, Tenant> tenantsById = tenantsById();
        return userRepository.findAll().stream()
                .filter(user -> !scoped() || sharesTenant(user))
                .map(user -> UserResponse.of(user, rolesById, tenantsById))
                .toList();
    }

    public UserResponse create(CreateUserRequest request) {
        String username = request.getUsername() != null ? request.getUsername().trim() : "";
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new InvalidInputException(
                    "Username must be 3-50 characters (letters, digits, '.', '_' or '-').");
        }
        validatePassword(request.getPassword());
        List<Role> roles = requireRoles(request.getRoleIds());
        roles.forEach(this::guardRoleAssignment);
        List<String> tenantIds = scoped()
                ? scopedCreateTenants(request.getTenantIds())
                : requireTenants(request.getTenantIds());
        if (userRepository.findByUsername(username).isPresent()) {
            throw new DuplicateEntityException("A user named '" + username + "' already exists.");
        }

        User user = new User(username, PasswordHasher.hash(request.getPassword()),
                roles.stream().map(Role::getId).toList());
        user.setTenantIds(tenantIds);
        userRepository.save(user);
        authorizationService.invalidateCache();
        auditLogger.log("USER_CREATE", username, "roles=" + roleNames(roles));
        return UserResponse.of(user, rolesById(), tenantsById());
    }

    public UserResponse update(String id, UpdateUserRequest request) {
        User user = requireUser(id);
        requireVisible(user);
        guardTargetUser(user);
        // account-wide changes on a target with foreign memberships would leak into
        // the other tenant - a scoped admin may only edit such a user's memberships
        if (request.getRoleIds() != null || request.getEnabled() != null) {
            requireFullyScoped(user);
        }

        List<Role> newRoles = request.getRoleIds() != null
                ? requireRoles(request.getRoleIds())
                : requireRoles(user.getRoleIds());
        boolean disabling = Boolean.FALSE.equals(request.getEnabled()) && user.isEnabled();
        boolean demoting = newRoles.stream().noneMatch(r -> r.hasPermission(Permission.SYSTEM_CONFIG))
                && anyRoleHoldsSystemConfig(user.getRoleIds());

        if (disabling && user.getId().equals(currentUser.getUserId())) {
            throw new InvalidInputException("You cannot disable your own account.");
        }
        if ((disabling || demoting) && isLastSuperAdmin(user)) {
            throw new InvalidInputException("Cannot remove the last enabled super admin.");
        }
        if (request.getRoleIds() != null) {
            newRoles.forEach(this::guardRoleAssignment);
            user.setRoleIds(newRoles.stream().map(Role::getId).toList());
        }
        if (request.getTenantIds() != null) {
            user.setTenantIds(scoped()
                    ? mergeTenants(user, request.getTenantIds())
                    : requireTenants(request.getTenantIds()));
        }
        if (request.getEnabled() != null) {
            user.setEnabled(request.getEnabled());
        }
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        authorizationService.invalidateCache();
        if (disabling) {
            sessionManager.invalidateSessionsForUser(user.getId());
        }
        auditLogger.log("USER_UPDATE", user.getUsername(),
                "roles=" + roleNames(newRoles) + " enabled=" + user.isEnabled());
        return UserResponse.of(user, rolesById(), tenantsById());
    }

    public void resetPassword(String id, String newPassword) {
        User user = requireUser(id);
        requireVisible(user);
        guardTargetUser(user);
        requireFullyScoped(user);
        validatePassword(newPassword);

        user.setPasswordHash(PasswordHasher.hash(newPassword));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        authorizationService.invalidateCache();
        sessionManager.invalidateSessionsForUser(user.getId());
        auditLogger.log("USER_PASSWORD_RESET", user.getUsername(), null);
    }

    public void delete(String id) {
        User user = requireUser(id);
        requireVisible(user);
        if (user.getId().equals(currentUser.getUserId())) {
            throw new InvalidInputException("You cannot delete your own account.");
        }
        guardTargetUser(user);
        requireFullyScoped(user);
        if (isLastSuperAdmin(user)) {
            throw new InvalidInputException("Cannot delete the last enabled super admin.");
        }

        userRepository.delete(user.getId());
        authorizationService.invalidateCache();
        sessionManager.invalidateSessionsForUser(user.getId());
        auditLogger.log("USER_DELETE", user.getUsername(), null);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidInputException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }
    }

    private User requireUser(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found."));
    }

    private List<Role> requireRoles(List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new InvalidInputException("At least one role is required.");
        }
        return roleIds.stream()
                .distinct()
                .map(id -> roleRepository.findById(id)
                        .orElseThrow(() -> new EntityNotFoundException("Role not found.")))
                .toList();
    }

    private Map<String, Role> rolesById() {
        return roleRepository.findAll().stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
    }

    private Map<String, Tenant> tenantsById() {
        return tenantRepository.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));
    }

    /** Tenants are optional (empty = no tenant), but every submitted id must exist. */
    private List<String> requireTenants(List<String> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return List.of();
        }
        return tenantIds.stream()
                .distinct()
                .map(id -> tenantRepository.findById(id)
                        .orElseThrow(() -> new EntityNotFoundException("Tenant not found."))
                        .getId())
                .toList();
    }

    private static String roleNames(List<Role> roles) {
        return roles.stream().map(Role::getName).collect(Collectors.joining(","));
    }

    /**
     * Managing a user who holds SYSTEM_CONFIG requires the actor to hold it too,
     * and touching a user with cross-tenant reach (TENANTS_VIEW_ALL) requires the
     * actor to have cross-tenant reach - otherwise a tenant-scoped admin could
     * reset a global admin's password and take over their account.
     */
    private void guardTargetUser(User target) {
        if (anyRoleHoldsSystemConfig(target.getRoleIds())
                && !currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            throw new AccessDeniedException("Only a super admin can manage super admin accounts.");
        }
        if (anyRoleHoldsTenantsViewAll(target.getRoleIds()) && !hasGlobalTenantAccess()) {
            throw new AccessDeniedException("Only a global admin can manage global admin accounts.");
        }
    }

    /**
     * Assigning a role that holds SYSTEM_CONFIG requires the actor to hold it too;
     * a role granting TENANTS_VIEW_ALL requires cross-tenant reach - otherwise a
     * tenant-scoped admin could escalate to global by assigning it to themselves.
     */
    private void guardRoleAssignment(Role role) {
        if (role.hasPermission(Permission.SYSTEM_CONFIG)
                && !currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            throw new AccessDeniedException("Only a super admin can assign the super admin role.");
        }
        if (role.hasPermission(Permission.TENANTS_VIEW_ALL) && !hasGlobalTenantAccess()) {
            throw new AccessDeniedException(
                    "Only a global admin can assign roles that see all tenants.");
        }
    }

    private boolean anyRoleHoldsSystemConfig(List<String> roleIds) {
        return roleIds.stream().anyMatch(roleId -> roleRepository.findById(roleId)
                .map(r -> r.hasPermission(Permission.SYSTEM_CONFIG))
                .orElse(false));
    }

    private boolean anyRoleHoldsTenantsViewAll(List<String> roleIds) {
        return roleIds.stream().anyMatch(roleId -> roleRepository.findById(roleId)
                .map(r -> r.hasPermission(Permission.TENANTS_VIEW_ALL))
                .orElse(false));
    }

    // ---- tenant-scoped administration ----
    // An actor with USERS_MANAGE but no cross-tenant reach is a "tenant admin":
    // they only see and manage members of their own tenants.

    private boolean hasGlobalTenantAccess() {
        return currentUser.hasPermission(Permission.TENANTS_VIEW_ALL)
                || currentUser.hasPermission(Permission.SYSTEM_CONFIG);
    }

    private boolean scoped() {
        return currentUser.isRbacActive() && !hasGlobalTenantAccess();
    }

    private Set<String> actorTenants() {
        return currentUser.getTenantIds();
    }

    private boolean sharesTenant(User user) {
        return user.getTenantIds().stream().anyMatch(actorTenants()::contains);
    }

    /** Users outside the actor's tenants are reported as nonexistent, like resources. */
    private void requireVisible(User user) {
        if (scoped() && !sharesTenant(user)) {
            throw new EntityNotFoundException("User not found.");
        }
    }

    /** Account-wide mutations need the whole account inside the actor's tenants. */
    private void requireFullyScoped(User user) {
        if (scoped() && !actorTenants().containsAll(user.getTenantIds())) {
            throw new AccessDeniedException(
                    "This user also belongs to another tenant - only their membership in your tenants can be changed here.");
        }
    }

    /** A scoped admin creates users only inside their own tenants (never tenantless). */
    private List<String> scopedCreateTenants(List<String> requested) {
        List<String> tenantIds = requireTenants(requested);
        if (tenantIds.isEmpty()) {
            throw new InvalidInputException("At least one of your tenants is required.");
        }
        if (!actorTenants().containsAll(tenantIds)) {
            throw new AccessDeniedException("You can only assign your own tenants.");
        }
        return tenantIds;
    }

    /**
     * A scoped admin only adds/removes their OWN tenants; the target's memberships
     * in other tenants are preserved untouched. The result may never be empty -
     * that would turn the user tenantless (globally visible) or lock the acting
     * admin out of their own account.
     */
    private List<String> mergeTenants(User target, List<String> requested) {
        List<String> validRequested = requireTenants(requested);
        Set<String> mine = actorTenants();
        List<String> merged = new java.util.ArrayList<>();
        target.getTenantIds().stream().filter(id -> !mine.contains(id)).forEach(merged::add);
        validRequested.stream().filter(mine::contains).filter(id -> !merged.contains(id)).forEach(merged::add);
        if (merged.isEmpty()) {
            throw new InvalidInputException(
                    "The user must keep at least one tenant.");
        }
        return merged;
    }

    /** True if this user is the only enabled user whose role holds SYSTEM_CONFIG. */
    private boolean isLastSuperAdmin(User target) {
        // resolve the super-admin roles once - findById re-reads the roles file per call
        Set<String> superAdminRoleIds = roleRepository.findAll().stream()
                .filter(r -> r.hasPermission(Permission.SYSTEM_CONFIG))
                .map(Role::getId)
                .collect(Collectors.toSet());
        if (!target.isEnabled() || target.getRoleIds().stream().noneMatch(superAdminRoleIds::contains)) {
            return false;
        }
        return userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(u -> u.getRoleIds().stream().anyMatch(superAdminRoleIds::contains))
                .allMatch(u -> u.getId().equals(target.getId()));
    }
}
