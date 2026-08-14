package br.com.fzdevx.domain.model;

import java.time.Instant;
import java.util.Objects;

public class ContainerExpiration {

    private String shortId;
    private String fullContainerId;
    private Instant expiresAt;
    private String repository;
    private String databaseName;
    private boolean deleteDatabaseOnExpiration;
    // Captured when deletion is armed: the expiration timer fires outside any
    // request scope, so the audit entry for the automatic drop needs the actor
    // and tenant recorded up front.
    private String deletionArmedBy;
    private String tenantId;

    public ContainerExpiration() {
    }

    public ContainerExpiration(String shortId, String fullContainerId, Instant expiresAt) {
        this.shortId = Objects.requireNonNull(shortId);
        this.fullContainerId = Objects.requireNonNull(fullContainerId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public ContainerExpiration(String shortId, String fullContainerId, Instant expiresAt,
                               String repository, String databaseName, boolean deleteDatabaseOnExpiration) {
        this(shortId, fullContainerId, expiresAt);
        this.repository = repository;
        this.databaseName = databaseName;
        this.deleteDatabaseOnExpiration = deleteDatabaseOnExpiration;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public String getShortId() {
        return shortId;
    }

    public void setShortId(String shortId) {
        this.shortId = shortId;
    }

    public String getFullContainerId() {
        return fullContainerId;
    }

    public void setFullContainerId(String fullContainerId) {
        this.fullContainerId = fullContainerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public boolean isDeleteDatabaseOnExpiration() {
        return deleteDatabaseOnExpiration;
    }

    public void setDeleteDatabaseOnExpiration(boolean deleteDatabaseOnExpiration) {
        this.deleteDatabaseOnExpiration = deleteDatabaseOnExpiration;
    }

    public String getDeletionArmedBy() {
        return deletionArmedBy;
    }

    public void setDeletionArmedBy(String deletionArmedBy) {
        this.deletionArmedBy = deletionArmedBy;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContainerExpiration that = (ContainerExpiration) o;
        return Objects.equals(shortId, that.shortId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortId);
    }
}
