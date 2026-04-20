package br.com.fzdevx.domain.model;

import java.time.Instant;
import java.util.Objects;


public class ManagedDatabase {

    private String repository;
    private String name;
    private boolean protectedFlag;
    private Instant appLastUsedAt;
    private Instant createdAt;
    private String description;
    private String lastRestoredFrom;
    private Instant lastRestoredAt;

    public ManagedDatabase() {
    }

    public ManagedDatabase(String repository, String name) {
        this.repository = repository;
        this.name = name;
        this.protectedFlag = false;
        this.createdAt = Instant.now();
    }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isProtectedFlag() { return protectedFlag; }
    public void setProtectedFlag(boolean protectedFlag) { this.protectedFlag = protectedFlag; }

    public Instant getAppLastUsedAt() { return appLastUsedAt; }
    public void setAppLastUsedAt(Instant appLastUsedAt) { this.appLastUsedAt = appLastUsedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLastRestoredFrom() { return lastRestoredFrom; }
    public void setLastRestoredFrom(String lastRestoredFrom) { this.lastRestoredFrom = lastRestoredFrom; }

    public Instant getLastRestoredAt() { return lastRestoredAt; }
    public void setLastRestoredAt(Instant lastRestoredAt) { this.lastRestoredAt = lastRestoredAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ManagedDatabase that = (ManagedDatabase) o;
        return Objects.equals(repository, that.repository) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repository, name);
    }
}
