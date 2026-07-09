package br.com.fzdevx.application.dto;

import java.util.List;

public class RestoreDumpRequest {

    private String dumpId;
    private String repository;
    private String targetDatabase;
    private boolean createDatabase;
    private String password;
    private List<String> selectedOptionalScripts;

    public String getDumpId() { return dumpId; }
    public void setDumpId(String dumpId) { this.dumpId = dumpId; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getTargetDatabase() { return targetDatabase; }
    public void setTargetDatabase(String targetDatabase) { this.targetDatabase = targetDatabase; }

    public boolean isCreateDatabase() { return createDatabase; }
    public void setCreateDatabase(boolean createDatabase) { this.createDatabase = createDatabase; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public List<String> getSelectedOptionalScripts() { return selectedOptionalScripts; }
    public void setSelectedOptionalScripts(List<String> selectedOptionalScripts) { this.selectedOptionalScripts = selectedOptionalScripts; }

    private String snapshotId;

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    private boolean webhookNotify;

    public boolean isWebhookNotify() { return webhookNotify; }
    public void setWebhookNotify(boolean webhookNotify) { this.webhookNotify = webhookNotify; }

    private String migrationMode;
    private String migrationSql;
    private String migrationSourceVersion;
    private String migrationTargetVersion;

    public String getMigrationMode() { return migrationMode; }
    public void setMigrationMode(String migrationMode) { this.migrationMode = migrationMode; }

    public String getMigrationSql() { return migrationSql; }
    public void setMigrationSql(String migrationSql) { this.migrationSql = migrationSql; }

    public String getMigrationSourceVersion() { return migrationSourceVersion; }
    public void setMigrationSourceVersion(String migrationSourceVersion) { this.migrationSourceVersion = migrationSourceVersion; }

    public String getMigrationTargetVersion() { return migrationTargetVersion; }
    public void setMigrationTargetVersion(String migrationTargetVersion) { this.migrationTargetVersion = migrationTargetVersion; }

    /** Owning tenant for a database created by this restore; resolved at prepare time. */
    private String tenantId;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
