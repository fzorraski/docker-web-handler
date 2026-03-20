package br.com.fzdevx.application.dto;

public class UpdateScheduleRequest {

    private String name;
    private String cronExpression;
    private String scheduledAt;
    private String containerId;
    private String containerName;
    private RunContainerRequest createConfig;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(String scheduledAt) { this.scheduledAt = scheduledAt; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public RunContainerRequest getCreateConfig() { return createConfig; }
    public void setCreateConfig(RunContainerRequest createConfig) { this.createConfig = createConfig; }
}
