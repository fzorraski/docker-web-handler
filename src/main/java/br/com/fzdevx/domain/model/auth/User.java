package br.com.fzdevx.domain.model.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class User {

    private String id;
    private String username;
    private String passwordHash;
    private List<String> roleIds = new ArrayList<>();
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;

    public User() {
    }

    public User(String username, String passwordHash, String roleId) {
        this(username, passwordHash, List.of(Objects.requireNonNull(roleId)));
    }

    public User(String username, String passwordHash, List<String> roleIds) {
        this.id = UUID.randomUUID().toString();
        this.username = Objects.requireNonNull(username);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.roleIds = new ArrayList<>(Objects.requireNonNull(roleIds));
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public List<String> getRoleIds() { return roleIds; }
    public void setRoleIds(List<String> roleIds) { this.roleIds = roleIds == null ? new ArrayList<>() : new ArrayList<>(roleIds); }

    public boolean hasRole(String roleId) { return roleIds.contains(roleId); }

    /**
     * Legacy pre-multi-role field, write-only: consumed by JSON-B when reading
     * a users.json written before roles became a list. Has no getter, so it is
     * never serialized back - files migrate to "roleIds" on the next save.
     */
    public void setRoleId(String roleId) {
        if (roleId != null && !roleIds.contains(roleId)) {
            roleIds.add(roleId);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User that = (User) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
