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
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        Map<String, Role> rolesById = roleRepository.findAll().stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        return userRepository.findAll().stream()
                .map(user -> UserResponse.of(user, rolesById.get(user.getRoleId())))
                .toList();
    }

    public UserResponse create(CreateUserRequest request) {
        String username = request.getUsername() != null ? request.getUsername().trim() : "";
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new InvalidInputException(
                    "Username must be 3-50 characters (letters, digits, '.', '_' or '-').");
        }
        validatePassword(request.getPassword());
        Role role = requireRole(request.getRoleId());
        guardRoleAssignment(role);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new DuplicateEntityException("A user named '" + username + "' already exists.");
        }

        User user = new User(username, PasswordHasher.hash(request.getPassword()), role.getId());
        userRepository.save(user);
        authorizationService.invalidateCache();
        auditLogger.log("USER_CREATE", username, "role=" + role.getName());
        return UserResponse.of(user, role);
    }

    public UserResponse update(String id, UpdateUserRequest request) {
        User user = requireUser(id);
        guardTargetUser(user);

        Role role = requireRole(request.getRoleId() != null ? request.getRoleId() : user.getRoleId());
        boolean disabling = Boolean.FALSE.equals(request.getEnabled()) && user.isEnabled();
        boolean demoting = !role.hasPermission(Permission.SYSTEM_CONFIG)
                && roleHoldsSystemConfig(user.getRoleId());

        if (disabling && user.getId().equals(currentUser.getUserId())) {
            throw new InvalidInputException("You cannot disable your own account.");
        }
        if ((disabling || demoting) && isLastSuperAdmin(user)) {
            throw new InvalidInputException("Cannot remove the last enabled super admin.");
        }
        if (request.getRoleId() != null) {
            guardRoleAssignment(role);
            user.setRoleId(role.getId());
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
                "role=" + role.getName() + " enabled=" + user.isEnabled());
        return UserResponse.of(user, role);
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

    private Role requireRole(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            throw new InvalidInputException("A role is required.");
        }
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Role not found."));
    }

    /** Managing a user who holds SYSTEM_CONFIG requires the actor to hold it too. */
    private void guardTargetUser(User target) {
        if (roleHoldsSystemConfig(target.getRoleId())
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

    private boolean roleHoldsSystemConfig(String roleId) {
        return roleRepository.findById(roleId)
                .map(r -> r.hasPermission(Permission.SYSTEM_CONFIG))
                .orElse(false);
    }

    /** True if this user is the only enabled user whose role holds SYSTEM_CONFIG. */
    private boolean isLastSuperAdmin(User target) {
        if (!target.isEnabled() || !roleHoldsSystemConfig(target.getRoleId())) {
            return false;
        }
        return userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(u -> roleHoldsSystemConfig(u.getRoleId()))
                .allMatch(u -> u.getId().equals(target.getId()));
    }
}
