package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.ScheduleRepository;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.RunContainerConfig;
import br.com.fzdevx.domain.model.ScheduleAction;
import br.com.fzdevx.domain.model.ScheduleType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Typed(PgScheduleRepository.class)
public class PgScheduleRepository implements ScheduleRepository {

    private static final String SELECT = """
            SELECT id, name, action, schedule_type, enabled, cron_expression, scheduled_at,
                   container_id, container_name, create_config, next_execution_at,
                   last_executed_at, last_execution_status, last_execution_message,
                   created_by, tenant_id, created_at
            FROM container_schedule
            """;

    private static final String UPSERT = """
            INSERT INTO container_schedule (id, name, action, schedule_type, enabled,
                cron_expression, scheduled_at, container_id, container_name, create_config,
                next_execution_at, last_executed_at, last_execution_status,
                last_execution_message, created_by, tenant_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                action = EXCLUDED.action,
                schedule_type = EXCLUDED.schedule_type,
                enabled = EXCLUDED.enabled,
                cron_expression = EXCLUDED.cron_expression,
                scheduled_at = EXCLUDED.scheduled_at,
                container_id = EXCLUDED.container_id,
                container_name = EXCLUDED.container_name,
                create_config = EXCLUDED.create_config,
                next_execution_at = EXCLUDED.next_execution_at,
                last_executed_at = EXCLUDED.last_executed_at,
                last_execution_status = EXCLUDED.last_execution_status,
                last_execution_message = EXCLUDED.last_execution_message,
                created_by = EXCLUDED.created_by,
                tenant_id = EXCLUDED.tenant_id,
                created_at = EXCLUDED.created_at
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(ContainerSchedule schedule) {
        jdbc.update(UPSERT, upsertParams(schedule));
    }

    @Override
    public boolean update(String id, java.util.function.Consumer<ContainerSchedule> mutator) {
        return jdbc.inTransaction(connection -> {
            Optional<ContainerSchedule> current = jdbc.queryOne(connection,
                    SELECT + "WHERE id = ? FOR UPDATE", PgScheduleRepository::map, id);
            if (current.isEmpty()) {
                return false;
            }
            ContainerSchedule schedule = current.get();
            mutator.accept(schedule);
            jdbc.update(connection, UPSERT, upsertParams(schedule));
            return true;
        });
    }

    private static Object[] upsertParams(ContainerSchedule schedule) {
        return new Object[]{
                schedule.getId(), schedule.getName(),
                schedule.getAction() == null ? null : schedule.getAction().name(),
                schedule.getScheduleType() == null ? null : schedule.getScheduleType().name(),
                schedule.isEnabled(), schedule.getCronExpression(), schedule.getScheduledAt(),
                schedule.getContainerId(), schedule.getContainerName(),
                JdbcSupport.JsonbValue.of(schedule.getCreateConfig()),
                schedule.getNextExecutionAt(), schedule.getLastExecutedAt(),
                schedule.getLastExecutionStatus(), schedule.getLastExecutionMessage(),
                schedule.getCreatedBy(), schedule.getTenantId(), schedule.getCreatedAt()};
    }

    @Override
    public void recordExecution(String id, String status, String message, Instant executedAt) {
        jdbc.update("""
                UPDATE container_schedule
                SET last_execution_status = ?, last_execution_message = ?, last_executed_at = ?
                WHERE id = ?
                """, status, message, executedAt, id);
    }

    @Override
    public void updateNextExecution(String id, Instant nextExecutionAt) {
        jdbc.update("UPDATE container_schedule SET next_execution_at = ? WHERE id = ?",
                nextExecutionAt, id);
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM container_schedule WHERE id = ?", id);
    }

    @Override
    public Optional<ContainerSchedule> findById(String id) {
        return jdbc.queryOne(SELECT + "WHERE id = ?", PgScheduleRepository::map, id);
    }

    @Override
    public List<ContainerSchedule> findByContainerId(String containerId) {
        if (containerId == null) {
            return List.of();
        }
        return jdbc.query(SELECT + "WHERE container_id = ?", PgScheduleRepository::map, containerId);
    }

    @Override
    public List<ContainerSchedule> findAll() {
        return jdbc.query(SELECT, PgScheduleRepository::map);
    }

    private static ContainerSchedule map(ResultSet rs) throws SQLException {
        ContainerSchedule schedule = new ContainerSchedule();
        schedule.setId(rs.getString("id"));
        schedule.setName(rs.getString("name"));
        String action = rs.getString("action");
        schedule.setAction(action == null ? null : ScheduleAction.valueOf(action));
        String type = rs.getString("schedule_type");
        schedule.setScheduleType(type == null ? null : ScheduleType.valueOf(type));
        schedule.setEnabled(rs.getBoolean("enabled"));
        schedule.setCronExpression(rs.getString("cron_expression"));
        schedule.setScheduledAt(JdbcSupport.instant(rs, "scheduled_at"));
        schedule.setContainerId(rs.getString("container_id"));
        schedule.setContainerName(rs.getString("container_name"));
        schedule.setCreateConfig(JdbcSupport.fromJson(rs, "create_config", RunContainerConfig.class));
        schedule.setNextExecutionAt(JdbcSupport.instant(rs, "next_execution_at"));
        schedule.setLastExecutedAt(JdbcSupport.instant(rs, "last_executed_at"));
        schedule.setLastExecutionStatus(rs.getString("last_execution_status"));
        schedule.setLastExecutionMessage(rs.getString("last_execution_message"));
        schedule.setCreatedBy(rs.getString("created_by"));
        schedule.setTenantId(rs.getString("tenant_id"));
        schedule.setCreatedAt(JdbcSupport.instant(rs, "created_at"));
        return schedule;
    }
}
