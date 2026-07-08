package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateUserRequest;
import br.com.fzdevx.application.dto.UpdateUserRequest;
import br.com.fzdevx.application.dto.UserResponse;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.AccessDeniedException;
import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
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
    AuthorizationService authorizationService;

    @Inject
    AuthSessionManager sessionManager;

    @Inject
    CurrentUser currentUser;

    @Inject
    AuditLogger auditLogger;

    public List<UserResponse> list() {
        Map<String, Role> rolesById = rolesById();
        return userRepository.findAll().stream()
                .map(user -> UserResponse.of(user, rolesById))
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
        if (userRepository.findByUsername(username).isPresent()) {
            throw new DuplicateEntityException("A user named '" + username + "' already exists.");
        }

        User user = new User(username, PasswordHasher.hash(request.getPassword()),
                roles.stream().map(Role::getId).toList());
        userRepository.save(user);
        authorizationService.invalidateCache();
        auditLogger.log("USER_CREATE", username, "roles=" + roleNames(roles));
        return UserResponse.of(user, rolesById());
    }

    public UserResponse update(String id, UpdateUserRequest request) {
        User user = requireUser(id);
        guardTargetUser(user);

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
        return UserResponse.of(user, rolesById());
    }

    public void resetPassword(String id, String newPassword) {
        User user = requireUser(id);
        guardTargetUser(user);
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
        if (user.getId().equals(currentUser.getUserId())) {
            throw new InvalidInputException("You cannot delete your own account.");
        }
        guardTargetUser(user);
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

    private static String roleNames(List<Role> roles) {
        return roles.stream().map(Role::getName).collect(Collectors.joining(","));
    }

    /** Managing a user who holds SYSTEM_CONFIG requires the actor to hold it too. */
    private void guardTargetUser(User target) {
        if (anyRoleHoldsSystemConfig(target.getRoleIds())
                && !currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            throw new AccessDeniedException("Only a super admin can manage super admin accounts.");
        }
    }

    /** Assigning a role that holds SYSTEM_CONFIG requires the actor to hold it too. */
    private void guardRoleAssignment(Role role) {
        if (role.hasPermission(Permission.SYSTEM_CONFIG)
                && !currentUser.hasPermission(Permission.SYSTEM_CONFIG)) {
            throw new AccessDeniedException("Only a super admin can assign the super admin role.");
        }
    }

    private boolean anyRoleHoldsSystemConfig(List<String> roleIds) {
        return roleIds.stream().anyMatch(roleId -> roleRepository.findById(roleId)
                .map(r -> r.hasPermission(Permission.SYSTEM_CONFIG))
                .orElse(false));
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
