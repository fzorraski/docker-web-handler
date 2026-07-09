package br.com.fzdevx.domain.model.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Tenant {

    private String id;
    private String name;
    private String description;
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
