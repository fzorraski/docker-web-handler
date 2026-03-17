package br.com.fzdevx.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class DatabaseSnapshot {

    public enum Format {
        CUSTOM, SQL
    }

    private String id;
    private String storedFilename;
    private String repository;
    private String sourceDatabaseName;
    private Format format;
    private String md5Hash;
    private Instant createdAt;
    private Instant expiresAt;
    private long fileSize;
    private String label;
    private String containerName;
    private String description;

    public DatabaseSnapshot() {
    }

    public DatabaseSnapshot(String repository, String sourceDatabaseName, Format format, String label) {
        this.id = UUID.randomUUID().toString();
        this.storedFilename = this.id + ".gz";
        this.repository = repository;
        this.sourceDatabaseName = sourceDatabaseName;
        this.format = format;
        this.createdAt = Instant.now();
        this.label = label;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStoredFilename() { return storedFilename; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getSourceDatabaseName() { return sourceDatabaseName; }
    public void setSourceDatabaseName(String sourceDatabaseName) { this.sourceDatabaseName = sourceDatabaseName; }

    public Format getFormat() { return format; }
    public void setFormat(Format format) { this.format = format; }

    public String getMd5Hash() { return md5Hash; }
    public void setMd5Hash(String md5Hash) { this.md5Hash = md5Hash; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabaseSnapshot that = (DatabaseSnapshot) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
