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
    ScheduleRepository scheduleRepository;

    @Inject
    ContainerExpirationService expirationService;

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
            if (request.getContainerName() != null && !request.getContainerName().isBlank()) {
                Optional<String> containerNameError = InputValidator.validateContainerName(request.getContainerName());
                if (containerNameError.isPresent()) {
                    throw new InvalidInputException(containerNameError.get());
                }
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

            schedule.setCreateConfig(request.getCreateConfig());
        }

        scheduleRepository.save(schedule);
        return schedule;
    }

    public ContainerSchedule update(String id, UpdateScheduleRequest request) {
        ContainerSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found: " + id));

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
            schedule.setScheduledAt(scheduledAt);
            schedule.setNextExecutionAt(scheduledAt);
        }

        if (request.getContainerId() != null) {
            schedule.setContainerId(request.getContainerId());
        }
        if (request.getContainerName() != null) {
            schedule.setContainerName(request.getContainerName());
        }
        if (request.getCreateConfig() != null) {
            request.getCreateConfig().setOperationsPassword(null);
            request.getCreateConfig().setOperationsPasswordValidated(false);
            schedule.setCreateConfig(request.getCreateConfig());
        }

        scheduleRepository.save(schedule);
        return schedule;
    }

    public void delete(String id) {
        if (scheduleRepository.findById(id).isEmpty()) {
            throw new EntityNotFoundException("Schedule not found: " + id);
        }
        scheduleRepository.delete(id);
    }

    public ContainerSchedule toggleEnabled(String id) {
        ContainerSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found: " + id));

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

        scheduleRepository.save(schedule);
        return schedule;
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
