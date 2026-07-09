package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.enterprise.context.RequestScoped;

import java.util.Set;

/**
 * Per-request identity holder, populated by the authentication filter after
 * session validation. When RBAC is not active, {@link #hasPermission} always
 * returns true so callers never need to check the auth mode themselves.
 */
@RequestScoped
public class CurrentUser {

    private String userId;
    private String username;
    private Set<Permission> permissions = Set.of();
    private Set<String> tenantIds = Set.of();
    private boolean rbacActive;

    public void set(String userId, String username, Set<Permission> permissions) {
        set(userId, username, permissions, Set.of());
    }

    public void set(String userId, String username, Set<Permission> permissions, Set<String> tenantIds) {
        this.userId = userId;
        this.username = username;
        this.permissions = permissions == null ? Set.of() : permissions;
        this.tenantIds = tenantIds == null ? Set.of() : tenantIds;
        this.rbacActive = true;
    }

    public boolean hasPermission(Permission permission) {
        return !rbacActive || permissions.contains(permission);
    }

    public boolean isRbacActive() {
        return rbacActive;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public Set<String> getTenantIds() {
        return tenantIds;
    }
}
