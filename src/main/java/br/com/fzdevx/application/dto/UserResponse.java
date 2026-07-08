package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.User;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public view of a user - never exposes the password hash. */
public record UserResponse(String id, String username, List<String> roleIds, List<String> roleNames,
                           boolean enabled, Instant createdAt, Instant lastLoginAt) {

    public static UserResponse of(User user, Map<String, Role> rolesById) {
        List<String> roleNames = user.getRoleIds().stream()
                .map(rolesById::get)
                .filter(Objects::nonNull)
                .map(Role::getName)
                .toList();
        return new UserResponse(user.getId(), user.getUsername(),
                List.copyOf(user.getRoleIds()), roleNames,
                user.isEnabled(), user.getCreatedAt(), user.getLastLoginAt());
    }
}
