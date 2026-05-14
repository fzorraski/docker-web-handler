package br.com.fzdevx.application.dto;

public class RemoveContainerRequest {

    private String containerId;
    private boolean deleteDatabase;
    private String repository;
    private String databaseName;
    private String operationsPassword;

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public boolean isDeleteDatabase() { return deleteDatabase; }
    public void setDeleteDatabase(boolean deleteDatabase) { this.deleteDatabase = deleteDatabase; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getOperationsPassword() { return operationsPassword; }
    public void setOperationsPassword(String operationsPassword) { this.operationsPassword = operationsPassword; }
}
