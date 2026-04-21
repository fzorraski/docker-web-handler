package br.com.fzdevx.application.dto;

public class UpgradeContainerRequest {

    private String containerId;
    private String newTag;
    private String password;
    private String migrationMode;
    private String migrationSql;
    private String migrationSourceVersion;
    private String migrationTargetVersion;

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getNewTag() { return newTag; }
    public void setNewTag(String newTag) { this.newTag = newTag; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getMigrationMode() { return migrationMode; }
    public void setMigrationMode(String migrationMode) { this.migrationMode = migrationMode; }

    public String getMigrationSql() { return migrationSql; }
    public void setMigrationSql(String migrationSql) { this.migrationSql = migrationSql; }

    public String getMigrationSourceVersion() { return migrationSourceVersion; }
    public void setMigrationSourceVersion(String migrationSourceVersion) { this.migrationSourceVersion = migrationSourceVersion; }

    public String getMigrationTargetVersion() { return migrationTargetVersion; }
    public void setMigrationTargetVersion(String migrationTargetVersion) { this.migrationTargetVersion = migrationTargetVersion; }

    public boolean hasMigration() {
        return migrationMode != null && !migrationMode.isBlank();
    }

    public boolean hasTagChange() {
        return newTag != null && !newTag.isBlank();
    }
}
