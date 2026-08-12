package br.com.fzdevx.domain.model.auth;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class Role {

    private String id;
    private String name;
    private String description;
    private Set<Permission> permissions = new LinkedHashSet<>();
    private boolean builtIn;
    /**
     * A built-in role a super admin has edited. The startup bootstrap re-seeds
     * built-in roles so new catalog permissions reach them, which would silently
     * undo those edits — customized roles are left alone instead.
     */
    private boolean customized;
    private Instant createdAt;

    public Role() {
    }

    public Role(String name, String description, Set<Permission> permissions) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name);
        this.description = description;
        this.permissions = copyOf(permissions);
        this.builtIn = false;
        this.createdAt = Instant.now();
    }

    public Role(String id, String name, String description, Set<Permission> permissions, boolean builtIn) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.description = description;
        this.permissions = copyOf(permissions);
        this.builtIn = builtIn;
        this.createdAt = Instant.now();
    }

    public boolean hasPermission(Permission permission) {
        return permissions != null && permissions.contains(permission);
    }

    private static Set<Permission> copyOf(Set<Permission> source) {
        return source == null || source.isEmpty()
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(EnumSet.copyOf(source));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Set<Permission> getPermissions() { return permissions; }
    public void setPermissions(Set<Permission> permissions) { this.permissions = permissions == null ? new LinkedHashSet<>() : permissions; }

    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }

    public boolean isCustomized() { return customized; }
    public void setCustomized(boolean customized) { this.customized = customized; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Role that = (Role) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
