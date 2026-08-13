package br.com.fzdevx.domain.model;

import java.time.Instant;
import java.util.List;

public class DatabaseMigrationRecord {

    private String databaseName;
    private String repository;
    private String sourceVersion;
    private String targetVersion;
    private List<String> versionsIncluded;
    private Integer totalStatements;
    private String mode;
    private Instant migratedAt;
    /** Who ran the migration; null when it predates tracking or RBAC is off. */
    private String migratedBy;

    public DatabaseMigrationRecord() {}

    public DatabaseMigrationRecord(String databaseName, String repository, String mode,
                                    String sourceVersion, String targetVersion,
                                    List<String> versionsIncluded, Integer totalStatements) {
        this.databaseName = databaseName;
        this.repository = repository;
        this.mode = mode;
        this.sourceVersion = sourceVersion;
        this.targetVersion = targetVersion;
        this.versionsIncluded = versionsIncluded;
        this.totalStatements = totalStatements;
        this.migratedAt = Instant.now();
    }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }

    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }

    public List<String> getVersionsIncluded() { return versionsIncluded; }
    public void setVersionsIncluded(List<String> versionsIncluded) { this.versionsIncluded = versionsIncluded; }

    public Integer getTotalStatements() { return totalStatements; }
    public void setTotalStatements(Integer totalStatements) { this.totalStatements = totalStatements; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Instant getMigratedAt() { return migratedAt; }
    public void setMigratedAt(Instant migratedAt) { this.migratedAt = migratedAt; }

    public String getMigratedBy() { return migratedBy; }
    public void setMigratedBy(String migratedBy) { this.migratedBy = migratedBy; }
}
