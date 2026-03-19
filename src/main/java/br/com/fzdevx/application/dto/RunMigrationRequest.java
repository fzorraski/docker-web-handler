package br.com.fzdevx.application.dto;

public class RunMigrationRequest {

    private String repository;
    private String targetDatabase;
    private String password;
    private String migrationMode;
    private String migrationSql;
    private String migrationSourceVersion;
    private String migrationTargetVersion;

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getTargetDatabase() { return targetDatabase; }
    public void setTargetDatabase(String targetDatabase) { this.targetDatabase = targetDatabase; }

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
}
