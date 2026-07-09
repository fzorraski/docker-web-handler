package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ContainerSchedule;

import java.util.List;
import java.util.Optional;

public interface ScheduleRepository {

    void save(ContainerSchedule schedule);

    /**
     * Atomically mutates the stored schedule - concurrent writes of other
     * fields (e.g. execution results) are not clobbered by a stale
     * full-object save. If the mutator throws, nothing is written.
     * Returns false when the schedule no longer exists.
     */
    boolean update(String id, java.util.function.Consumer<ContainerSchedule> mutator);

    /**
     * Targeted write of the execution result fields, so a finishing run can
     * never clobber a concurrent admin edit of the schedule definition.
     */
    void recordExecution(String id, String status, String message, java.time.Instant executedAt);

    /** Targeted write of the next planned execution time. */
    void updateNextExecution(String id, java.time.Instant nextExecutionAt);

    void delete(String id);

    Optional<ContainerSchedule> findById(String id);

    List<ContainerSchedule> findByContainerId(String containerId);

    List<ContainerSchedule> findAll();
}
