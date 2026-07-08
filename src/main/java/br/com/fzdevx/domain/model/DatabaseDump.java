package br.com.fzdevx.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class DatabaseDump {

    public enum Format {
        SQL, CUSTOM, COMPRESSED
    }

    private String id;
    private String originalFilename;
    private String storedFilename;
    private String databaseName;
    private String version;
    private String md5Hash;
    private Instant uploadedAt;
    private Instant expiresAt;
    private long fileSize;
    private Format format;
    private String description;
    private Instant lastUsedAt;
    private String createdBy;

    public DatabaseDump() {
    }

    public DatabaseDump(String originalFilename, String databaseName, String version, Instant expiresAt, long fileSize) {
        this.id = UUID.randomUUID().toString();
        this.originalFilename = originalFilename;
        this.storedFilename = this.id + ".gz";
        this.databaseName = databaseName;
        this.version = version;
        this.uploadedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.fileSize = fileSize;
        this.format = detectFormat(originalFilename);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public static Format detectFormat(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".gz") || lower.endsWith(".tar.gz")) {
            return Format.COMPRESSED;
        }
        if (lower.endsWith(".dump")) {
            return Format.CUSTOM;
        }
        return Format.SQL;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getStoredFilename() { return storedFilename; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getMd5Hash() { return md5Hash; }
    public void setMd5Hash(String md5Hash) { this.md5Hash = md5Hash; }

    public Format getFormat() { return format; }
    public void setFormat(Format format) { this.format = format; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabaseDump that = (DatabaseDump) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
