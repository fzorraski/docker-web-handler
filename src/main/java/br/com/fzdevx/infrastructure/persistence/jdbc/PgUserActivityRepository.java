package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.dto.ActivityReportCriteria;
import br.com.fzdevx.application.dto.ActivityReportResult;
import br.com.fzdevx.domain.model.UserActivitySummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The daily activity roll-up: writes by the summariser, reads by the reports
 * screen. PostgreSQL only - this is a derived store, so the file backend has no
 * equivalent (see ActivitySummaryService).
 */
@ApplicationScoped
@Typed(PgUserActivityRepository.class)
public class PgUserActivityRepository {

    /**
     * Recomputes one day from the audit trail. The count is REPLACED, never
     * added to: the statement derives it from source, so running the same day
     * twice - a retry, or a second instance firing the same timer - lands on the
     * same numbers. An additive upsert would silently double every count.
     */
    private static final String ROLL_UP_DAY = """
            INSERT INTO user_activity_daily (activity_day, actor, action, tenant_id, event_count)
            SELECT ?, COALESCE(actor, 'system'), action, tenant_id, count(*)
            FROM audit_log
            WHERE occurred_at >= ? AND occurred_at < ?
            GROUP BY 2, 3, 4
            ON CONFLICT (activity_day, actor, action, COALESCE(tenant_id, ''))
            DO UPDATE SET event_count = EXCLUDED.event_count
            """;

    @Inject
    JdbcSupport jdbc;

    /**
     * Rolls up one calendar day and advances the watermark to it, in a single
     * transaction so an interrupted backfill cannot leave the watermark ahead of
     * the data.
     *
     * @param dayStart inclusive, dayEnd exclusive - the day's bounds as instants,
     *                 computed in the summariser's zone
     * @return the number of summary rows written for that day
     */
    public int rollUpDay(LocalDate day, Instant dayStart, Instant dayEnd) {
        return jdbc.inTransaction(connection -> {
            int rows = jdbc.update(connection, ROLL_UP_DAY, Date.valueOf(day), dayStart, dayEnd);
            jdbc.update(connection, """
                    INSERT INTO activity_summary_state (id, summarised_through) VALUES (1, ?)
                    ON CONFLICT (id) DO UPDATE SET summarised_through = EXCLUDED.summarised_through
                    """, Date.valueOf(day));
            return rows;
        });
    }

    /** The last day already summarised, or empty on a fresh install. */
    public Optional<LocalDate> summarisedThrough() {
        return jdbc.queryOne("SELECT summarised_through FROM activity_summary_state WHERE id = 1",
                        rs -> localDate(rs, "summarised_through"))
                .filter(java.util.Objects::nonNull);
    }

    /** Parks the watermark without rolling anything up (empty audit trail). */
    public void setSummarisedThrough(LocalDate day) {
        jdbc.update("""
                INSERT INTO activity_summary_state (id, summarised_through) VALUES (1, ?)
                ON CONFLICT (id) DO UPDATE SET summarised_through = EXCLUDED.summarised_through
                """, Date.valueOf(day));
    }

    /** Timestamp of the oldest audit entry, for the first backfill. */
    public Optional<Instant> oldestAuditEntry() {
        return jdbc.queryOne("SELECT min(occurred_at) AS oldest FROM audit_log",
                        rs -> JdbcSupport.instant(rs, "oldest"))
                .filter(java.util.Objects::nonNull);
    }

    /** Day-by-day rows, newest first. */
    public ActivityReportResult search(ActivityReportCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendFilters(where, params, criteria);

        long total = jdbc.queryOne("SELECT count(*) FROM user_activity_daily" + where,
                rs -> rs.getLong(1), params.toArray()).orElse(0L);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(criteria.size());
        pageParams.add((long) criteria.page() * criteria.size());
        List<UserActivitySummary> rows = jdbc.query(
                "SELECT activity_day, actor, action, tenant_id, event_count FROM user_activity_daily"
                        + where + " ORDER BY activity_day DESC, event_count DESC, actor, action"
                        + " LIMIT ? OFFSET ?",
                PgUserActivityRepository::map, pageParams.toArray());

        return new ActivityReportResult(rows, total);
    }

    /**
     * Totals per user and action across the whole range - the shape the reports
     * screen renders ("alice opened the terminal 12 times this month"). The day
     * is null on these rows because they span the range.
     */
    public ActivityReportResult totalsByUser(ActivityReportCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendFilters(where, params, criteria);

        long total = jdbc.queryOne(
                "SELECT count(*) FROM (SELECT 1 FROM user_activity_daily" + where
                        + " GROUP BY actor, action) grouped",
                rs -> rs.getLong(1), params.toArray()).orElse(0L);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(criteria.size());
        pageParams.add((long) criteria.page() * criteria.size());
        List<UserActivitySummary> rows = jdbc.query(
                "SELECT actor, action, sum(event_count) AS event_count FROM user_activity_daily"
                        + where + " GROUP BY actor, action ORDER BY sum(event_count) DESC, actor, action"
                        + " LIMIT ? OFFSET ?",
                rs -> new UserActivitySummary(null, rs.getString("actor"), rs.getString("action"),
                        null, rs.getLong("event_count")),
                pageParams.toArray());

        return new ActivityReportResult(rows, total);
    }

    /** Distinct action names present in the summary, for the filter dropdown. */
    public List<String> distinctActions() {
        return jdbc.query("SELECT DISTINCT action FROM user_activity_daily ORDER BY action",
                rs -> rs.getString(1));
    }

    private static void appendFilters(StringBuilder where, List<Object> params,
                                      ActivityReportCriteria criteria) {
        if (criteria.from() != null) {
            where.append(" AND activity_day >= ?");
            params.add(Date.valueOf(criteria.from()));
        }
        if (criteria.to() != null) {
            where.append(" AND activity_day <= ?");
            params.add(Date.valueOf(criteria.to()));
        }
        if (criteria.actor() != null && !criteria.actor().isBlank()) {
            where.append(" AND lower(actor) = lower(?)");
            params.add(criteria.actor().trim());
        }
        if (criteria.action() != null && !criteria.action().isBlank()) {
            where.append(" AND action = ?");
            params.add(criteria.action().trim());
        }
    }

    private static UserActivitySummary map(ResultSet rs) throws SQLException {
        return new UserActivitySummary(
                localDate(rs, "activity_day"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("tenant_id"),
                rs.getLong("event_count"));
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
