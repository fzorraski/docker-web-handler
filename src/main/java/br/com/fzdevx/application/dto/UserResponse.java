package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;

import java.time.Instant;

/** Public view of a user - never exposes the password hash. */
public record UserResponse(String id, String username, String roleId, String roleName,
                           boolean enabled, Instant createdAt, Instant lastLoginAt) {

    public static UserResponse of(User user, Role role) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRoleId(),
                role != null ? role.getName() : null,
                user.isEnabled(), user.getCreatedAt(), user.getLastLoginAt());
    }
}
