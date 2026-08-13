package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateScheduleRequest;
import br.com.fzdevx.application.dto.UpdateScheduleRequest;
import br.com.fzdevx.application.port.ScheduleRepository;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.ScheduleAction;
import br.com.fzdevx.domain.model.ScheduleType;
import br.com.fzdevx.domain.shared.CronParser;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ManageScheduleUseCase {

    @Inject
    br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantVisibility tenantVisibility;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantSharing tenantSharing;

    @Inject
    br.com.fzdevx.infrastructure.docker.ContainerTenantGuard containerTenantGuard;

    @Inject
    ScheduleRepository scheduleRepository;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    ContainerProtectionService protectionService;

    @Inject
    ContainerSchedulingService schedulingService;

    public ContainerSchedule createAndSchedule(CreateScheduleRequest request) {
        ContainerSchedule schedule = create(request);
        schedulingService.scheduleNext(schedule);
        return schedule;
    }

    public ContainerSchedule updateAndReschedule(String id, UpdateScheduleRequest request) {
        ContainerSchedule schedule = update(id, request);
        schedulingService.cancel(id);
        if (schedule.isEnabled()) {
            schedulingService.scheduleNext(schedule);
        }
        return schedule;
    }

    public ContainerSchedule toggleAndReschedule(String id) {
        ContainerSchedule schedule = toggleEnabled(id);
        if (schedule.isEnabled()) {
            schedulingService.scheduleNext(schedule);
        } else {
            schedulingService.cancel(id);
        }
        return schedule;
    }

    public void deleteAndCancel(String id) {
        schedulingService.cancel(id);
        delete(id);
    }

    public void executeNow(String id) {
        var schedule = scheduleRepository.findById(id);
        if (schedule.isEmpty()) {
            throw new EntityNotFoundException("Schedule not found: " + id);
        }
        if (!schedule.get().isEnabled()) {
            throw new InvalidInputException("Cannot execute a disabled schedule.");
        }
        schedulingService.executeNow(id);
    }

    public ContainerSchedule create(CreateScheduleRequest request) {
        // Validate name
        Optional<String> nameError = InputValidator.validateScheduleName(request.getName());
        if (nameError.isPresent()) {
            throw new InvalidInputException(nameError.get());
        }

        // Parse action and type
        ScheduleAction action;
        try {
            action = ScheduleAction.valueOf(request.getAction());
        } catch (Exception e) {
            throw new InvalidInputException("Invalid action. Must be START, STOP, CREATE, or REMOVE.");
        }

        ScheduleType type;
        try {
            type = ScheduleType.valueOf(request.getScheduleType());
        } catch (Exception e) {
            throw new InvalidInputException("Invalid schedule type. Must be ONE_TIME or RECURRING.");
        }

        ContainerSchedule schedule = new ContainerSchedule(request.getName(), action, type);
        schedule.setCreatedBy(actorResolver.usernameOrSystem());
        schedule.setTenantId(tenantVisibility.resolveCreationTenant(request.getTenantId(), request.isNoTenant()));
        List<String> sharedWithTenants =
                tenantSharing.normalize(request.getSharedWithTenants(), schedule.getTenantId());
        tenantSharing.firstUnknown(sharedWithTenants).ifPresent(unknown -> {
            throw new InvalidInputException("Unknown tenant: " + unknown);
        });
        schedule.setSharedWithTenants(sharedWithTenants);

        if (type == ScheduleType.RECURRING) {
            Optional<String> cronError = InputValidator.validateCronExpression(request.getCronExpression());
            if (cronError.isPresent()) {
                throw new InvalidInputException(cronError.get());
            }
            schedule.setCronExpression(request.getCronExpression());
            Instant next = CronParser.nextExecution(request.getCronExpression(), Instant.now());
            if (next == null) {
                throw new InvalidInputException("No valid next execution found within 366 days.");
            }
            schedule.setNextExecutionAt(next);
        } else {
            if (request.getScheduledAt() == null || request.getScheduledAt().isBlank()) {
                throw new InvalidInputException("Scheduled time is required for one-time schedules.");
            }
            Instant scheduledAt = parseInstant(request.getScheduledAt());
            if (scheduledAt.isBefore(Instant.now())) {
                throw new InvalidInputException("Scheduled time must be in the future.");
            }
            schedule.setScheduledAt(scheduledAt);
            schedule.setNextExecutionAt(scheduledAt);
        }

        // Set action-specific fields
        boolean targetsContainer = action == ScheduleAction.START
                || action == ScheduleAction.STOP
                || action == ScheduleAction.REMOVE;

        if (targetsContainer) {
            if (request.getContainerId() == null || request.getContainerId().isBlank()) {
                throw new InvalidInputException("Container ID is required for " + action + " schedules.");
            }
            Optional<String> containerIdError = InputValidator.validateContainerId(request.getContainerId());
            if (containerIdError.isPresent()) {
                throw new InvalidInputException(containerIdError.get());
            }
            // the scheduler fires outside any request scope where no tenant check can
            // run - guard the target here, or a schedule becomes a cross-tenant
            // stop/remove of another squad's container
            containerTenantGuard.requireVisible(request.getContainerId());
            if (request.getContainerName() != null && !request.getContainerName().isBlank()) {
                Optional<String> containerNameError = InputValidator.validateContainerName(request.getContainerName());
                if (containerNameError.isPresent()) {
                    throw new InvalidInputException(containerNameError.get());
                }
            }

            // A protected container cannot be scheduled to stop or be removed.
            if ((action == ScheduleAction.STOP || action == ScheduleAction.REMOVE)
                    && protectionService.isProtectedContainer(request.getContainerId())) {
                throw new InvalidInputException(
                        "This container is protected and cannot be scheduled to be stopped or removed.");
            }

            // Validate schedule times against the container's expiration.
            // No point scheduling anything after the container will already be gone.
            String shortId = request.getContainerId().length() > 10
                    ? request.getContainerId().substring(0, 10)
                    : request.getContainerId();
            Instant expiresAt = expirationService.getExpiresAt(shortId);

            if (expiresAt != null) {
                if (type == ScheduleType.ONE_TIME && schedule.getScheduledAt() != null
                        && schedule.getScheduledAt().isAfter(expiresAt)) {
                    throw new InvalidInputException(
                            "The scheduled time is after the container's expiration. "
                            + "The container will already be removed by then.");
                }
                if (type == ScheduleType.RECURRING && schedule.getNextExecutionAt() != null
                        && schedule.getNextExecutionAt().isAfter(expiresAt)) {
                    throw new InvalidInputException(
                            "The first cron occurrence is after the container's expiration. "
                            + "The container will already be removed before this schedule ever fires.");
                }
            }

            // --- Conflict detection against existing schedules ---
            List<ContainerSchedule> existing = scheduleRepository.findByContainerId(request.getContainerId())
                    .stream().filter(ContainerSchedule::isEnabled).toList();

            boolean hasRemove = existing.stream()
                    .anyMatch(s -> s.getAction() == ScheduleAction.REMOVE);

            if (hasRemove) {
                throw new InvalidInputException(
                        "A REMOVE schedule already exists for this container. "
                        + "No new schedules can be added because the container will be removed.");
            }

            if (action == ScheduleAction.REMOVE && !existing.isEmpty()) {
                String names = existing.stream()
                        .map(s -> s.getName() + " (" + s.getAction() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("");
                throw new InvalidInputException(
                        "Cannot add a REMOVE schedule while other active schedules exist for this container: "
                        + names + ". Delete them first or they will become orphaned.");
            }

            boolean hasSameAction = existing.stream()
                    .anyMatch(s -> s.getAction() == action);
            if (hasSameAction) {
                throw new InvalidInputException(
                        "A " + action + " schedule already exists for this container. "
                        + "Remove the existing one before creating a new one.");
            }

            schedule.setContainerId(request.getContainerId());
            schedule.setContainerName(request.getContainerName());
        } else if (action == ScheduleAction.CREATE) {
            if (request.getCreateConfig() == null) {
                throw new InvalidInputException("Container creation config is required for CREATE schedules.");
            }
            // Strip sensitive fields before persisting
            request.getCreateConfig().setOperationsPassword(null);
            request.getCreateConfig().setOperationsPasswordValidated(false);

            // Safety: recurring CREATE must not have database deletion — each container
            // would try to drop the same database on expiry, killing sibling containers.
            if (type == ScheduleType.RECURRING) {
                if (request.getCreateConfig().isDeleteDatabaseOnExpiration()) {
                    throw new InvalidInputException(
                            "Recurring CREATE schedules cannot enable 'delete database on expiration'. "
                            + "Each scheduled container would compete to drop the same database.");
                }
                // Also clear expiration to prevent unbounded container accumulation.
                // Recurring containers should be managed by a separate STOP schedule or manually.
                request.getCreateConfig().setExpiresAt(null);
            }

            // scheduled creates run outside a request scope - the container inherits
            // the schedule's tenant and sharing through the persisted config, not the
            // actor. Always overwritten from the schedule's VALIDATED list: the config
            // deserializes a sharedWithTenants field of its own, which would otherwise
            // reach the docker label without the unknown-tenant check
            request.getCreateConfig().setTenantId(schedule.getTenantId());
            request.getCreateConfig().setSharedWithTenants(schedule.getSharedWithTenants());
            schedule.setCreateConfig(request.getCreateConfig());
        }

        scheduleRepository.save(schedule);
        return schedule;
    }

    public ContainerSchedule update(String id, UpdateScheduleRequest request) {
        // The cross-tenant Docker check runs BEFORE the mutation: it is a
        // Docker API round trip and must not execute while the mutator holds
        // the store's row/file lock.
        if (request.getContainerId() != null) {
            Optional<String> containerIdError = InputValidator.validateContainerId(request.getContainerId());
            if (containerIdError.isPresent()) {
                throw new InvalidInputException(containerIdError.get());
            }
            containerTenantGuard.requireVisible(request.getContainerId());
        }
        // atomic mutation: an admin edit must not write back stale execution
        // fields over a concurrently finishing run (and vice versa); the
        // mutated object is captured so the response reflects exactly what
        // this request wrote (no non-atomic re-read)
        ContainerSchedule[] result = new ContainerSchedule[1];
        boolean found = scheduleRepository.update(id, schedule -> {
            applyUpdate(schedule, request);
            result[0] = schedule;
        });
        if (!found) {
            throw new EntityNotFoundException("Schedule not found: " + id);
        }
        return result[0];
    }

    private void applyUpdate(ContainerSchedule schedule, UpdateScheduleRequest request) {
        if (request.getName() != null) {
            Optional<String> nameError = InputValidator.validateScheduleName(request.getName());
            if (nameError.isPresent()) {
                throw new InvalidInputException(nameError.get());
            }
            schedule.setName(request.getName());
        }

        if (schedule.getScheduleType() == ScheduleType.RECURRING && request.getCronExpression() != null) {
            Optional<String> cronError = InputValidator.validateCronExpression(request.getCronExpression());
            if (cronError.isPresent()) {
                throw new InvalidInputException(cronError.get());
            }
            schedule.setCronExpression(request.getCronExpression());
            Instant next = CronParser.nextExecution(request.getCronExpression(), Instant.now());
            if (next == null) {
                throw new InvalidInputException("No valid next execution found within 366 days.");
            }
            schedule.setNextExecutionAt(next);
        }

        if (schedule.getScheduleType() == ScheduleType.ONE_TIME && request.getScheduledAt() != null) {
            Instant scheduledAt = parseInstant(request.getScheduledAt());
            if (scheduledAt.isBefore(Instant.now())) {
                throw new InvalidInputException("Scheduled time must be in the future.");
            }
            schedule.setScheduledAt(scheduledAt);
            schedule.setNextExecutionAt(scheduledAt);
        }

        if (request.getContainerId() != null) {
            // validated and tenant-guarded in update() BEFORE the mutation -
            // no Docker call may run while the store lock is held
            schedule.setContainerId(request.getContainerId());
        }
        if (request.getContainerName() != null && !request.getContainerName().isBlank()) {
            Optional<String> containerNameError = InputValidator.validateContainerName(request.getContainerName());
            if (containerNameError.isPresent()) {
                throw new InvalidInputException(containerNameError.get());
            }
            schedule.setContainerName(request.getContainerName());
        }
        if (request.getCreateConfig() != null) {
            request.getCreateConfig().setOperationsPassword(null);
            request.getCreateConfig().setOperationsPasswordValidated(false);
            if (schedule.getScheduleType() == ScheduleType.RECURRING) {
                if (request.getCreateConfig().isDeleteDatabaseOnExpiration()) {
                    throw new InvalidInputException(
                            "Recurring CREATE schedules cannot enable 'delete database on expiration'.");
                }
                request.getCreateConfig().setExpiresAt(null);
            }
            // the schedule's tenant and sharing are fixed at creation and survive
            // config edits; overwriting also discards any smuggled shared list
            request.getCreateConfig().setTenantId(schedule.getTenantId());
            request.getCreateConfig().setSharedWithTenants(schedule.getSharedWithTenants());
            schedule.setCreateConfig(request.getCreateConfig());
        }
    }

    /**
     * Replaces who the schedule is shared with (and, for cross-tenant admins,
     * its owner). Sharing a schedule grants edit/disable/execute-now, so the
     * decision stays with the owning tenant - the controller enforces that.
     * The persisted createConfig is kept in sync: it is what a CREATE schedule
     * reads at fire time, outside any request scope.
     */
    public ContainerSchedule updateSharing(String id, List<String> requestedShares,
                                           String newTenantId, boolean changeOwner) {
        ContainerSchedule current = scheduleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found: " + id));

        // normalized against the EFFECTIVE owner; a transfer to "no tenant"
        // strips the OUTGOING owner so its id cannot linger on the share list
        String effectiveOwner = changeOwner && newTenantId != null ? newTenantId : current.getTenantId();
        List<String> sharedWithTenants = tenantSharing.normalize(requestedShares, effectiveOwner);
        tenantSharing.firstUnknown(sharedWithTenants).ifPresent(unknown -> {
            throw new InvalidInputException("Unknown tenant: " + unknown);
        });
        if (changeOwner && newTenantId != null
                && tenantSharing.firstUnknown(List.of(newTenantId)).isPresent()) {
            throw new InvalidInputException("Unknown tenant: " + newTenantId);
        }

        ContainerSchedule[] updated = new ContainerSchedule[1];
        boolean found = scheduleRepository.update(id, schedule -> {
            schedule.setSharedWithTenants(sharedWithTenants);
            if (changeOwner) {
                schedule.setTenantId(newTenantId);
            }
            if (schedule.getCreateConfig() != null) {
                schedule.getCreateConfig().setTenantId(schedule.getTenantId());
                schedule.getCreateConfig().setSharedWithTenants(sharedWithTenants);
            }
            updated[0] = schedule;
        });
        if (!found) {
            throw new EntityNotFoundException("Schedule not found: " + id);
        }
        return updated[0];
    }

    public void delete(String id) {
        if (scheduleRepository.findById(id).isEmpty()) {
            throw new EntityNotFoundException("Schedule not found: " + id);
        }
        scheduleRepository.delete(id);
    }

    public ContainerSchedule toggleEnabled(String id) {
        // atomic mutation: the guard evaluates against the freshest state, so a
        // stale read can never re-arm an executed one-time schedule; the
        // mutated object is captured so the response reflects this request's
        // write without a non-atomic re-read
        ContainerSchedule[] result = new ContainerSchedule[1];
        boolean found = scheduleRepository.update(id, schedule -> {
            result[0] = schedule;
            // Block re-enabling a one-time schedule that already executed
            if (!schedule.isEnabled()
                    && schedule.getScheduleType() == ScheduleType.ONE_TIME
                    && schedule.getLastExecutedAt() != null) {
                throw new InvalidInputException(
                        "Cannot re-enable a one-time schedule that has already been executed. Create a new schedule instead.");
            }

            schedule.setEnabled(!schedule.isEnabled());

            if (schedule.isEnabled()) {
                // Recompute next execution
                if (schedule.getScheduleType() == ScheduleType.RECURRING) {
                    Instant next = CronParser.nextExecution(schedule.getCronExpression(), Instant.now());
                    schedule.setNextExecutionAt(next);
                } else if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
                    schedule.setNextExecutionAt(schedule.getScheduledAt());
                }
            }
        });
        if (!found) {
            throw new EntityNotFoundException("Schedule not found: " + id);
        }
        return result[0];
    }

    public Optional<ContainerSchedule> findById(String id) {
        return scheduleRepository.findById(id);
    }

    public List<ContainerSchedule> findAll() {
        return scheduleRepository.findAll();
    }

    public List<ContainerSchedule> findByContainerId(String containerId) {
        return scheduleRepository.findByContainerId(containerId);
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            // Try ISO_LOCAL_DATE_TIME format
            try {
                LocalDateTime ldt = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception e2) {
                throw new InvalidInputException("Invalid datetime format: " + value);
            }
        }
    }
}
