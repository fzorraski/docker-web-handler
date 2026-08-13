package br.com.fzdevx.domain.model.auth;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Tenant {

    private String id;
    private String name;
    private String description;
    /** Repositories members may run containers from; null = all allowed repositories. */
    private List<String> enabledRepositories;
    /** Repositories whose database connection members may use; null = all configured. */
    private List<String> enabledDatabases;
    /** Badge colour as #RRGGBB; null falls back to a hue derived from the id. */
    private String color;
    private Instant createdAt;
    private Instant updatedAt;

    public Tenant() {
    }

    public Tenant(String name, String description) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name);
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getEnabledRepositories() { return enabledRepositories; }
    public void setEnabledRepositories(List<String> enabledRepositories) { this.enabledRepositories = enabledRepositories; }

    public List<String> getEnabledDatabases() { return enabledDatabases; }
    public void setEnabledDatabases(List<String> enabledDatabases) { this.enabledDatabases = enabledDatabases; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tenant that = (Tenant) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
