package br.com.fzdevx.application.dto;

import java.util.List;

public class CiCreateEnvironmentRequest {

    private String repository;
    private String tag;
    private String environmentName;
    private List<String> envVars;
    private Integer ttlMinutes;
    private Long memoryMb;
    private String databaseName;
    private boolean createDatabase;
    private String dumpId;
    private String snapshotId;
    private boolean deleteDatabaseOnExpiration;
    private List<String> selectedOptionalScripts;
    private String pipelineId;

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public String getEnvironmentName() { return environmentName; }
    public void setEnvironmentName(String environmentName) { this.environmentName = environmentName; }

    public List<String> getEnvVars() { return envVars; }
    public void setEnvVars(List<String> envVars) { this.envVars = envVars; }

    public Integer getTtlMinutes() { return ttlMinutes; }
    public void setTtlMinutes(Integer ttlMinutes) { this.ttlMinutes = ttlMinutes; }

    public Long getMemoryMb() { return memoryMb; }
    public void setMemoryMb(Long memoryMb) { this.memoryMb = memoryMb; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public boolean isCreateDatabase() { return createDatabase; }
    public void setCreateDatabase(boolean createDatabase) { this.createDatabase = createDatabase; }

    public String getDumpId() { return dumpId; }
    public void setDumpId(String dumpId) { this.dumpId = dumpId; }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    public boolean isDeleteDatabaseOnExpiration() { return deleteDatabaseOnExpiration; }
    public void setDeleteDatabaseOnExpiration(boolean deleteDatabaseOnExpiration) { this.deleteDatabaseOnExpiration = deleteDatabaseOnExpiration; }

    public List<String> getSelectedOptionalScripts() { return selectedOptionalScripts; }
    public void setSelectedOptionalScripts(List<String> selectedOptionalScripts) { this.selectedOptionalScripts = selectedOptionalScripts; }

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
}
