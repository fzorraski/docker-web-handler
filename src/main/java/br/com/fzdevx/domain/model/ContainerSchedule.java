package br.com.fzdevx.domain.model;

import br.com.fzdevx.application.dto.RunContainerRequest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class ContainerSchedule {

    private String id;
    private String name;
    private ScheduleAction action;
    private ScheduleType scheduleType;
    private boolean enabled;
    private Instant createdAt;

    // For RECURRING schedules — 5-field cron expression
    private String cronExpression;

    // For ONE_TIME schedules
    private Instant scheduledAt;

    // For START/STOP actions
    private String containerId;
    private String containerName;

    // For CREATE action — embedded container creation config
    private RunContainerRequest createConfig;

    // Execution tracking
    private Instant nextExecutionAt;
    private Instant lastExecutedAt;
    private String lastExecutionStatus; // SUCCESS, FAILED, SKIPPED
    private String lastExecutionMessage;

    public ContainerSchedule() {
    }

    public ContainerSchedule(String name, ScheduleAction action, ScheduleType scheduleType) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name);
        this.action = Objects.requireNonNull(action);
        this.scheduleType = Objects.requireNonNull(scheduleType);
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ScheduleAction getAction() { return action; }
    public void setAction(ScheduleAction action) { this.action = action; }

    public ScheduleType getScheduleType() { return scheduleType; }
    public void setScheduleType(ScheduleType scheduleType) { this.scheduleType = scheduleType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public RunContainerRequest getCreateConfig() { return createConfig; }
    public void setCreateConfig(RunContainerRequest createConfig) { this.createConfig = createConfig; }

    public Instant getNextExecutionAt() { return nextExecutionAt; }
    public void setNextExecutionAt(Instant nextExecutionAt) { this.nextExecutionAt = nextExecutionAt; }

    public Instant getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(Instant lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }

    public String getLastExecutionStatus() { return lastExecutionStatus; }
    public void setLastExecutionStatus(String lastExecutionStatus) { this.lastExecutionStatus = lastExecutionStatus; }

    public String getLastExecutionMessage() { return lastExecutionMessage; }
    public void setLastExecutionMessage(String lastExecutionMessage) { this.lastExecutionMessage = lastExecutionMessage; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContainerSchedule that = (ContainerSchedule) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
