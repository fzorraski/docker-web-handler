package br.com.fzdevx.model;

import java.util.List;

public class RunContainerRequest {

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
}
