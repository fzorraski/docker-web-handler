package br.com.fzdevx.application.dto;

public class CiDestroyResponse {

    private boolean destroyed;
    private String containerId;
    private boolean databaseDropped;
    private String databaseDropError;

    public boolean isDestroyed() { return destroyed; }
    public void setDestroyed(boolean destroyed) { this.destroyed = destroyed; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public boolean isDatabaseDropped() { return databaseDropped; }
    public void setDatabaseDropped(boolean databaseDropped) { this.databaseDropped = databaseDropped; }

    public String getDatabaseDropError() { return databaseDropError; }
    public void setDatabaseDropError(String databaseDropError) { this.databaseDropError = databaseDropError; }
}
