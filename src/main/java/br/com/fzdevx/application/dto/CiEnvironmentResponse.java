package br.com.fzdevx.application.dto;

import java.util.Map;

public class CiEnvironmentResponse {

    private String containerId;
    private String environmentName;
    private String status;
    private Map<String, String> portMappings;
    private String databaseName;
    private String repository;
    private String tag;
    private String expiresAt;
    private String pipelineId;
    private String createdAt;

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getEnvironmentName() { return environmentName; }
    public void setEnvironmentName(String environmentName) { this.environmentName = environmentName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, String> getPortMappings() { return portMappings; }
    public void setPortMappings(Map<String, String> portMappings) { this.portMappings = portMappings; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
