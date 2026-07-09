package br.com.fzdevx.domain.model;

import java.util.List;
import java.util.Map;

public class RunContainerConfig {

    private String repository;

    private String tag;

    private String containerName;

    private List<String> envVars;

    private String expiresAt;

    private Long memoryMb;

    private String databaseName;

    private boolean deleteDatabaseOnExpiration;

    private String dumpId;

    private boolean createDatabase;

    private List<String> selectedOptionalScripts;

    private String snapshotId;

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public List<String> getEnvVars() {
        return envVars;
    }

    public void setEnvVars(List<String> envVars) {
        this.envVars = envVars;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getMemoryMb() {
        return memoryMb;
    }

    public void setMemoryMb(Long memoryMb) {
        this.memoryMb = memoryMb;
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

    public String getDumpId() {
        return dumpId;
    }

    public void setDumpId(String dumpId) {
        this.dumpId = dumpId;
    }

    public boolean isCreateDatabase() {
        return createDatabase;
    }

    public void setCreateDatabase(boolean createDatabase) {
        this.createDatabase = createDatabase;
    }

    public List<String> getSelectedOptionalScripts() {
        return selectedOptionalScripts;
    }

    public void setSelectedOptionalScripts(List<String> selectedOptionalScripts) {
        this.selectedOptionalScripts = selectedOptionalScripts;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    private boolean webhookNotify;
    private String operationsPassword;
    private boolean operationsPasswordValidated;
    private String migrationMode;
    private String migrationSql;
    private String migrationSourceVersion;
    private String migrationTargetVersion;

    public boolean isWebhookNotify() { return webhookNotify; }
    public void setWebhookNotify(boolean webhookNotify) { this.webhookNotify = webhookNotify; }

    public String getOperationsPassword() { return operationsPassword; }
    public void setOperationsPassword(String operationsPassword) { this.operationsPassword = operationsPassword; }

    public boolean isOperationsPasswordValidated() { return operationsPasswordValidated; }
    public void setOperationsPasswordValidated(boolean operationsPasswordValidated) { this.operationsPasswordValidated = operationsPasswordValidated; }

    public String getMigrationMode() { return migrationMode; }
    public void setMigrationMode(String migrationMode) { this.migrationMode = migrationMode; }

    public String getMigrationSql() { return migrationSql; }
    public void setMigrationSql(String migrationSql) { this.migrationSql = migrationSql; }

    public String getMigrationSourceVersion() { return migrationSourceVersion; }
    public void setMigrationSourceVersion(String migrationSourceVersion) { this.migrationSourceVersion = migrationSourceVersion; }

    public String getMigrationTargetVersion() { return migrationTargetVersion; }
    public void setMigrationTargetVersion(String migrationTargetVersion) { this.migrationTargetVersion = migrationTargetVersion; }

    private Map<String, String> extraLabels;

    public Map<String, String> getExtraLabels() { return extraLabels; }
    public void setExtraLabels(Map<String, String> extraLabels) { this.extraLabels = extraLabels; }

    /** Owning tenant, resolved at prepare/create time; scheduled creates reuse the persisted value. */
    private String tenantId;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public RunContainerConfig copy() {
        RunContainerConfig c = new RunContainerConfig();
        c.repository = this.repository;
        c.tag = this.tag;
        c.containerName = this.containerName;
        c.envVars = this.envVars != null ? List.copyOf(this.envVars) : null;
        c.expiresAt = this.expiresAt;
        c.memoryMb = this.memoryMb;
        c.databaseName = this.databaseName;
        c.deleteDatabaseOnExpiration = this.deleteDatabaseOnExpiration;
        c.dumpId = this.dumpId;
        c.createDatabase = this.createDatabase;
        c.selectedOptionalScripts = this.selectedOptionalScripts != null ? List.copyOf(this.selectedOptionalScripts) : null;
        c.snapshotId = this.snapshotId;
        c.webhookNotify = this.webhookNotify;
        c.operationsPassword = this.operationsPassword;
        c.operationsPasswordValidated = this.operationsPasswordValidated;
        c.migrationMode = this.migrationMode;
        c.migrationSql = this.migrationSql;
        c.migrationSourceVersion = this.migrationSourceVersion;
        c.migrationTargetVersion = this.migrationTargetVersion;
        c.extraLabels = this.extraLabels != null ? Map.copyOf(this.extraLabels) : null;
        c.tenantId = this.tenantId;
        return c;
    }
}
