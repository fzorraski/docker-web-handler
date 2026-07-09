package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.application.port.ScheduleRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
@jakarta.enterprise.inject.Typed(JsonFileScheduleRepository.class)
public class JsonFileScheduleRepository
        extends AbstractJsonFileRepository<ContainerSchedule>
        implements ScheduleRepository {

    private static final java.lang.reflect.Type SCHEDULE_LIST_TYPE =
            new ArrayList<ContainerSchedule>() {}.getClass().getGenericSuperclass();

    public JsonFileScheduleRepository(
            @ConfigProperty(name = "schedule.storage.file",
                    defaultValue = "data/schedules.json") String filePath) {
        super(filePath, SCHEDULE_LIST_TYPE);
    }

    @Override
    public void save(ContainerSchedule schedule) {
        saveEntity(schedule, s -> s.getId().equals(schedule.getId()));
    }

    @Override
    public boolean update(String id, java.util.function.Consumer<ContainerSchedule> mutator) {
        return updateEntity(s -> s.getId().equals(id), mutator);
    }

    @Override
    public void recordExecution(String id, String status, String message, java.time.Instant executedAt) {
        updateEntity(s -> s.getId().equals(id), s -> {
            s.setLastExecutionStatus(status);
            s.setLastExecutionMessage(message);
            s.setLastExecutedAt(executedAt);
        });
    }

    @Override
    public void updateNextExecution(String id, java.time.Instant nextExecutionAt) {
        updateEntity(s -> s.getId().equals(id), s -> s.setNextExecutionAt(nextExecutionAt));
    }

    @Override
    public void delete(String id) {
        deleteEntity(s -> s.getId().equals(id));
    }

    @Override
    public Optional<ContainerSchedule> findById(String id) {
        return findFirst(s -> s.getId().equals(id));
    }

    @Override
    public List<ContainerSchedule> findByContainerId(String containerId) {
        if (containerId == null) return List.of();
        return findAllMatching(s -> containerId.equals(s.getContainerId()));
    }

    @Override
    public List<ContainerSchedule> findAll() {
        return findAllEntities();
    }
}
