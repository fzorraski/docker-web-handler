package br.com.fzdevx.application.dto;

public class CiHealthResponse {

    private String containerId;
    private boolean containerRunning;
    private Boolean databaseAccessible;
    private String containerStatus;
    private String message;

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public boolean isContainerRunning() { return containerRunning; }
    public void setContainerRunning(boolean containerRunning) { this.containerRunning = containerRunning; }

    public Boolean getDatabaseAccessible() { return databaseAccessible; }
    public void setDatabaseAccessible(Boolean databaseAccessible) { this.databaseAccessible = databaseAccessible; }

    public String getContainerStatus() { return containerStatus; }
    public void setContainerStatus(String containerStatus) { this.containerStatus = containerStatus; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
