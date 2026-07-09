package br.com.fzdevx.application.dto;

public class CreateScheduleRequest {

    private String name;
    private String action;       // START, STOP, CREATE, REMOVE
    private String scheduleType; // ONE_TIME, RECURRING
    private String cronExpression;
    private String scheduledAt;  // ISO instant for ONE_TIME
    private String containerId;
    private String containerName;
    private RunContainerRequest createConfig;
    private String operationsPassword;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }

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

    public String getOperationsPassword() { return operationsPassword; }
    public void setOperationsPassword(String operationsPassword) { this.operationsPassword = operationsPassword; }

    private String tenantId;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
