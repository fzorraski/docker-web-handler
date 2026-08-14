package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.ScheduleRepository;
import br.com.fzdevx.application.usecase.RunContainerUseCase;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.ScheduleAction;
import br.com.fzdevx.domain.model.ScheduleType;
import br.com.fzdevx.domain.shared.CronParser;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;

@ApplicationScoped
public class ContainerSchedulingService {

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;

    @Inject
    DockerClient dockerClient;

    @Inject
    ScheduleRepository scheduleRepository;

    @Inject
    RunContainerUseCase runContainerUseCase;

    @Inject
    ResourceCounterService resourceCounterService;

    @Inject
    MemoryGuardService memoryGuardService;

    @Inject
    ContainerProtectionService protectionService;

    @Inject
    ContainerListBroadcaster broadcaster;

    @ConfigProperty(name = "container.scheduling.enabled", defaultValue = "false")
    boolean schedulingEnabled;

    @ConfigProperty(name = "schedule.executor.threads", defaultValue = "4")
    int executorThreads;

    void onStartup(@Observes StartupEvent event) {
        if (!schedulingEnabled) {
            Log.info("Container scheduling is disabled.");
            return;
        }
        scheduler = Executors.newScheduledThreadPool(executorThreads);
        reloadSchedules();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean isEnabled() {
        return schedulingEnabled;
    }

    /**
     * Schedule or reschedule a schedule for its next execution.
     */
    public void scheduleNext(ContainerSchedule schedule) {
        if (!schedulingEnabled || scheduler == null) return;
        if (!schedule.isEnabled()) return;

        cancel(schedule.getId());

        Instant nextExec;
        if (schedule.getScheduleType() == ScheduleType.RECURRING) {
            nextExec = CronParser.nextExecution(schedule.getCronExpression(), Instant.now());
        } else {
            nextExec = schedule.getScheduledAt();
        }

        if (nextExec == null) {
            updateStatus(schedule, "FAILED", "No valid next execution found within 366 days.");
            return;
        }

        schedule.setNextExecutionAt(nextExec);
        try {
            scheduleRepository.updateNextExecution(schedule.getId(), nextExec);
        } catch (RuntimeException e) {
            // persistence failed but the in-JVM timer below still fires; the
            // stored nextExecutionAt catches up on the next successful write
            Log.errorf("Failed to persist next execution of schedule '%s': %s",
                    schedule.getName(), e.getMessage());
        }

        long delayMs = nextExec.toEpochMilli() - System.currentTimeMillis();
        if (delayMs <= 0) delayMs = 1;

        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeSchedule(schedule.getId()),
                delayMs,
                TimeUnit.MILLISECONDS
        );

        scheduledTasks.put(schedule.getId(), future);
        Log.infof("Scheduled '%s' (%s) next execution at %s",
                schedule.getName(), schedule.getId(),
                DateTimeFormatter.ISO_INSTANT.format(nextExec));
    }

    /**
     * Cancel a scheduled task.
     */
    public void cancel(String id) {
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Cancel and delete all schedules targeting a specific container.
     * Called when a container is removed so orphaned schedules don't accumulate.
     */
    public void removeSchedulesByContainer(String containerId) {
        removeSchedulesByContainer(containerId, null);
    }

    public void removeSchedulesByContainer(String containerId, String excludeScheduleId) {
        List<ContainerSchedule> schedules = scheduleRepository.findByContainerId(containerId);
        for (ContainerSchedule schedule : schedules) {
            if (schedule.getId().equals(excludeScheduleId)) continue;
            cancel(schedule.getId());
            scheduleRepository.delete(schedule.getId());
            Log.infof("Removed schedule '%s' — target container %s was deleted.", schedule.getName(), containerId);
        }
    }

    public void transferSchedules(String oldContainerId, String newContainerId) {
        List<ContainerSchedule> schedules = scheduleRepository.findByContainerId(oldContainerId);
        for (ContainerSchedule schedule : schedules) {
            cancel(schedule.getId());
            schedule.setContainerId(newContainerId);
            // targeted mutation: must not clobber a concurrent admin edit
            scheduleRepository.update(schedule.getId(), stored -> stored.setContainerId(newContainerId));
            if (schedule.isEnabled()) {
                scheduleNext(schedule);
            }
            Log.infof("Transferred schedule '%s' from container %s to %s.",
                    schedule.getName(), oldContainerId, newContainerId);
        }
    }

    /**
     * Execute a schedule immediately (manual trigger).
     */
    public void executeNow(String id) {
        if (scheduler == null) return;
        scheduler.submit(() -> executeSchedule(id));
    }

    private void reloadSchedules() {
        List<ContainerSchedule> all = scheduleRepository.findAll();
        Log.infof("Reloading %d persisted container schedules.", all.size());

        for (ContainerSchedule schedule : all) {
            if (!schedule.isEnabled()) continue;

            if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
                // Execute missed one-time schedules
                if (schedule.getScheduledAt() != null
                        && schedule.getScheduledAt().isBefore(Instant.now())
                        && schedule.getLastExecutedAt() == null) {
                    Log.infof("Schedule '%s' was missed, executing now.", schedule.getName());
                    scheduler.submit(() -> executeSchedule(schedule.getId()));
                } else if (schedule.getLastExecutedAt() == null) {
                    scheduleNext(schedule);
                }
                // Already executed one-time schedules are not rescheduled
            } else {
                // RECURRING: skip missed, schedule next future occurrence
                scheduleNext(schedule);
            }
        }
    }

    private void executeSchedule(String scheduleId) {
        ContainerSchedule schedule;
        try {
            schedule = scheduleRepository.findById(scheduleId).orElse(null);
        } catch (RuntimeException e) {
            // Store unreachable (e.g. transient app-DB outage): the action has
            // not run yet, so retry the whole cycle instead of silently losing
            // the schedule until the next restart.
            Log.errorf("Cannot load schedule %s (%s) - retrying in %d minutes.",
                    scheduleId, e.getMessage(), STORE_RETRY_MINUTES);
            rearmRetry(scheduleId);
            return;
        }
        if (schedule == null) {
            Log.warnf("Schedule %s not found, skipping execution.", scheduleId);
            scheduledTasks.remove(scheduleId);
            return;
        }

        Log.infof("Executing schedule '%s' (action=%s)", schedule.getName(), schedule.getAction());

        try {
            switch (schedule.getAction()) {
                case START -> executeStart(schedule);
                case STOP -> executeStop(schedule);
                case CREATE -> executeCreate(schedule);
                case REMOVE -> executeRemove(schedule);
            }
        } catch (Exception e) {
            updateStatus(schedule, "FAILED", e.getMessage());
            Log.errorf("Schedule '%s' failed: %s", schedule.getName(), e.getMessage());
        } finally {
            scheduledTasks.remove(scheduleId);
        }

        // Post-execution: reschedule or disable. Guarded so a transient store
        // error can never leave a recurring schedule silently dead - worst
        // case the in-memory copy drives the reschedule and persistence
        // catches up on the next cycle.
        try {
            // Re-read schedule to get updated status (executeStart/Stop may have written SKIPPED)
            schedule = scheduleRepository.findById(scheduleId).orElse(schedule);

            if (schedule.getScheduleType() == ScheduleType.RECURRING && schedule.isEnabled()) {
                // Auto-disable recurring START/STOP schedules if the container no longer exists.
                // Prevents perpetual SKIPPED noise in the logs.
                if ((schedule.getAction() == ScheduleAction.START
                            || schedule.getAction() == ScheduleAction.STOP
                            || schedule.getAction() == ScheduleAction.REMOVE)
                        && "SKIPPED".equals(schedule.getLastExecutionStatus())
                        && schedule.getLastExecutionMessage() != null
                        && schedule.getLastExecutionMessage().contains("Container not found")) {
                    // targeted mutation: must not clobber a concurrent admin edit
                    scheduleRepository.update(scheduleId, stored -> {
                        stored.setEnabled(false);
                        stored.setLastExecutionMessage(stored.getLastExecutionMessage()
                                + " — Schedule auto-disabled (container removed).");
                    });
                    Log.warnf("Schedule '%s' auto-disabled: target container no longer exists.", schedule.getName());
                } else {
                    scheduleNext(schedule);
                }
            } else if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
                disableOneTime(scheduleId, 1);
            }
        } catch (RuntimeException e) {
            Log.errorf("Post-execution handling of schedule '%s' failed: %s", schedule.getName(), e.getMessage());
            if (schedule.getScheduleType() == ScheduleType.RECURRING && schedule.isEnabled()) {
                scheduleNext(schedule);
            } else if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
                // the disable MUST eventually persist: an executed one-time
                // schedule left enabled with no lastExecutedAt is re-run as
                // "missed" by reloadSchedules after a restart
                retryDisableOneTimeLater(scheduleId, 2);
            }
        }
    }

    private static final int MAX_DISABLE_RETRIES = 12;

    /** Persists the one-time disable via targeted mutation, retrying on store failures. */
    private void disableOneTime(String scheduleId, int attempt) {
        try {
            scheduleRepository.update(scheduleId, stored -> stored.setEnabled(false));
        } catch (RuntimeException e) {
            Log.errorf("Failed to disable executed one-time schedule %s (attempt %d): %s",
                    scheduleId, attempt, e.getMessage());
            retryDisableOneTimeLater(scheduleId, attempt + 1);
        }
    }

    private void retryDisableOneTimeLater(String scheduleId, int nextAttempt) {
        if (scheduler == null || nextAttempt > MAX_DISABLE_RETRIES) {
            Log.errorf("Giving up disabling one-time schedule %s - it may re-execute after a restart.", scheduleId);
            return;
        }
        scheduler.schedule(() -> disableOneTime(scheduleId, nextAttempt),
                STORE_RETRY_MINUTES, TimeUnit.MINUTES);
    }

    private static final long STORE_RETRY_MINUTES = 5;

    /** Re-arms an execution attempt after a transient store failure. */
    private void rearmRetry(String scheduleId) {
        if (scheduler == null) {
            scheduledTasks.remove(scheduleId);
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeSchedule(scheduleId), STORE_RETRY_MINUTES, TimeUnit.MINUTES);
        // a manual execute-now can land here while the regular timer is still
        // armed - cancel the displaced future or the action fires twice
        ScheduledFuture<?> displaced = scheduledTasks.put(scheduleId, future);
        if (displaced != null && displaced != future) {
            displaced.cancel(false);
        }
    }

    private void executeStart(ContainerSchedule schedule) {
        String containerId = schedule.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            updateStatus(schedule, "FAILED", "No container ID configured.");
            return;
        }

        try {
            // Check if container exists
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withIdFilter(List.of(containerId))
                    .exec();

            if (containers.isEmpty()) {
                updateStatus(schedule, "SKIPPED", "Container not found: " + containerId);
                return;
            }

            String state = containers.getFirst().getState();
            if ("running".equalsIgnoreCase(state)) {
                updateStatus(schedule, "SKIPPED", "Container is already running.");
                return;
            }

            String memoryError = memoryGuardService.checkMemoryFor(null);
            if (memoryError != null) {
                updateStatus(schedule, "FAILED", memoryError);
                return;
            }

            dockerClient.startContainerCmd(containerId).exec();
            updateStatus(schedule, "SUCCESS", "Container started successfully.");
            broadcaster.notifyChange();
        } catch (Exception e) {
            updateStatus(schedule, "FAILED", "Failed to start container: " + e.getMessage());
        }
    }

    private void executeStop(ContainerSchedule schedule) {
        String containerId = schedule.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            updateStatus(schedule, "FAILED", "No container ID configured.");
            return;
        }

        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withIdFilter(List.of(containerId))
                    .exec();

            if (containers.isEmpty()) {
                updateStatus(schedule, "SKIPPED", "Container not found: " + containerId);
                return;
            }

            String state = containers.getFirst().getState();
            if (!"running".equalsIgnoreCase(state)) {
                updateStatus(schedule, "SKIPPED", "Container is not running.");
                return;
            }

            if (protectionService.isProtectedImage(containers.getFirst().getImage())) {
                updateStatus(schedule, "SKIPPED", "Container image is protected and cannot be stopped.");
                return;
            }

            dockerClient.stopContainerCmd(containerId).exec();
            updateStatus(schedule, "SUCCESS", "Container stopped successfully.");
            broadcaster.notifyChange();
        } catch (Exception e) {
            updateStatus(schedule, "FAILED", "Failed to stop container: " + e.getMessage());
        }
    }

    private void executeRemove(ContainerSchedule schedule) {
        String containerId = schedule.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            updateStatus(schedule, "FAILED", "No container ID configured.");
            return;
        }

        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withIdFilter(List.of(containerId))
                    .exec();

            if (containers.isEmpty()) {
                updateStatus(schedule, "SKIPPED", "Container not found: " + containerId);
                return;
            }

            if (protectionService.isProtectedImage(containers.getFirst().getImage())) {
                updateStatus(schedule, "SKIPPED", "Container image is protected and cannot be removed.");
                return;
            }

            // Stop first if running, then remove
            String state = containers.getFirst().getState();
            if ("running".equalsIgnoreCase(state)) {
                try {
                    dockerClient.stopContainerCmd(containerId).exec();
                } catch (Exception ignored) {
                }
            }

            dockerClient.removeContainerCmd(containerId).exec();
            updateStatus(schedule, "SUCCESS", "Container removed successfully.");
            broadcaster.notifyChange();

            // Clean up other schedules targeting this container, excluding the current one
            removeSchedulesByContainer(containerId, schedule.getId());
        } catch (Exception e) {
            updateStatus(schedule, "FAILED", "Failed to remove container: " + e.getMessage());
        }
    }

    private void executeCreate(ContainerSchedule schedule) {
        if (schedule.getCreateConfig() == null) {
            updateStatus(schedule, "FAILED", "No container creation config.");
            return;
        }

        var storedConfig = schedule.getCreateConfig();

        // Work on a copy to avoid mutating the persisted config
        var config = storedConfig.copy();

        // For recurring CREATE, append timestamp to container name to avoid conflicts
        if (schedule.getScheduleType() == ScheduleType.RECURRING
                && config.getContainerName() != null && !config.getContainerName().isBlank()) {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(Instant.now().atZone(ZoneId.systemDefault()));
            config.setContainerName(config.getContainerName() + "-" + timestamp);
        }

        // Safety net: force-clear dangerous options for recurring CREATE even if
        // they somehow got through validation (e.g. manual JSON edit of schedules.json)
        if (schedule.getScheduleType() == ScheduleType.RECURRING) {
            config.setDeleteDatabaseOnExpiration(false);
            config.setExpiresAt(null);
        }

        // Mark password as validated (it was validated at schedule creation time)
        config.setOperationsPasswordValidated(true);
        // deferred deletions armed by this run belong to whoever wrote the
        // schedule, not to "system" - the guard verified their grant at write time
        config.setOnBehalfOf(schedule.getCreatedBy());

        // Use a logging consumer instead of SSE
        StringBuilder logBuilder = new StringBuilder();
        runContainerUseCase.execute(config, event -> {
            Log.infof("[Schedule %s] %s: %s", schedule.getName(), event.getStep(), event.getMessage());
            if (event.getType() == ContainerEvent.EventType.ERROR) {
                logBuilder.append("ERROR: ").append(event.getMessage());
            }
        });

        if (logBuilder.isEmpty()) {
            updateStatus(schedule, "SUCCESS", "Container created successfully.");
            broadcaster.notifyChange();
        } else {
            updateStatus(schedule, "FAILED", logBuilder.toString());
        }
    }

    private void updateStatus(ContainerSchedule schedule, String status, String message) {
        Instant executedAt = Instant.now();
        // keep the in-memory copy in sync for the post-execution logic
        schedule.setLastExecutedAt(executedAt);
        schedule.setLastExecutionStatus(status);
        schedule.setLastExecutionMessage(message);
        try {
            // targeted write: never clobbers a concurrent admin edit of the definition;
            // a failed status write must never break the execution/reschedule flow
            scheduleRepository.recordExecution(schedule.getId(), status, message, executedAt);
        } catch (RuntimeException e) {
            Log.errorf("Failed to persist execution status of schedule '%s': %s",
                    schedule.getName(), e.getMessage());
        }
        if ("SUCCESS".equals(status)) {
            resourceCounterService.increment(ResourceCounterService.SCHEDULES_EXECUTED);
        }
    }
}
