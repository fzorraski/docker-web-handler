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
import java.time.ZoneId;
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

    /**
     * Day-by-day rows, newest first. The ORDER BY carries every column of the
     * unique index: a partial ordering lets tied rows repeat or vanish between
     * pages, since LIMIT/OFFSET re-sorts each request independently.
     *
     * <p>Rows are grouped again on the way out because the merged source can
     * hold two rows for the same day and actor - one summarised, one live -
     * only while the roll-up is mid-flight for that day.</p>
     */
    public ActivityReportResult search(ActivityReportCriteria criteria, ZoneId zone) {
        Merged base = merged(criteria.from(), criteria.to(), zone);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>(base.params());
        appendFilters(where, params, criteria);
        String grouped = base.sql() + where + " GROUP BY activity_day, actor, action, tenant_id";

        long total = jdbc.queryOne("SELECT count(*) FROM (SELECT 1 FROM " + grouped + ") g",
                rs -> rs.getLong(1), params.toArray()).orElse(0L);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(criteria.size());
        pageParams.add((long) criteria.page() * criteria.size());
        List<UserActivitySummary> rows = jdbc.query(
                "SELECT activity_day, actor, action, tenant_id, sum(event_count) AS event_count FROM "
                        + grouped + " ORDER BY activity_day DESC, sum(event_count) DESC, actor, action,"
                        + " COALESCE(tenant_id, '') LIMIT ? OFFSET ?",
                PgUserActivityRepository::map, pageParams.toArray());

        return new ActivityReportResult(rows, total);
    }

    /**
     * Totals per user and action across the whole range - the shape the reports
     * screen renders ("alice opened the terminal 12 times this month"). The day
     * is null on these rows because they span the range.
     */
    public ActivityReportResult totalsByUser(ActivityReportCriteria criteria, ZoneId zone) {
        Merged base = merged(criteria.from(), criteria.to(), zone);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>(base.params());
        appendFilters(where, params, criteria);

        long total = jdbc.queryOne(
                "SELECT count(*) FROM (SELECT 1 FROM " + base.sql() + where
                        + " GROUP BY actor, action) grouped",
                rs -> rs.getLong(1), params.toArray()).orElse(0L);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(criteria.size());
        pageParams.add((long) criteria.page() * criteria.size());
        List<UserActivitySummary> rows = jdbc.query(
                "SELECT actor, action, sum(event_count) AS event_count FROM " + base.sql()
                        + where + " GROUP BY actor, action ORDER BY sum(event_count) DESC, actor, action"
                        + " LIMIT ? OFFSET ?",
                rs -> new UserActivitySummary(null, rs.getString("actor"), rs.getString("action"),
                        null, rs.getLong("event_count")),
                pageParams.toArray());

        return new ActivityReportResult(rows, total);
    }

    /**
     * Every row in the window, unpaginated - the dashboard aggregates these
     * itself. Bounded by {@link br.com.fzdevx.application.dto.ActivityOverviewCriteria#MAX_RANGE_DAYS}
     * upstream and by the roll-up's own grain (one row per day, actor, action
     * and tenant), so this stays a few thousand rows even for a busy year.
     */
    public List<UserActivitySummary> rowsForRange(LocalDate from, LocalDate to, String tenantId,
                                                  ZoneId zone) {
        if (from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        Merged base = merged(from, to, zone);
        List<Object> params = new ArrayList<>(base.params());
        StringBuilder where = new StringBuilder(" WHERE activity_day >= ? AND activity_day <= ?");
        params.add(Date.valueOf(from));
        params.add(Date.valueOf(to));
        if (tenantId != null && !tenantId.isBlank()) {
            where.append(" AND tenant_id = ?");
            params.add(tenantId.trim());
        }
        return jdbc.query(
                "SELECT activity_day, actor, action, tenant_id, sum(event_count) AS event_count FROM "
                        + base.sql() + where + " GROUP BY activity_day, actor, action, tenant_id",
                PgUserActivityRepository::map, params.toArray());
    }

    /** A derived table plus the parameters it binds, ready to be filtered. */
    private record Merged(String sql, List<Object> params) {
    }

    /**
     * The roll-up up to the watermark, unioned with the raw audit trail after
     * it. Without the second half every report would end at the last complete
     * day - today's work would be invisible until the summariser next runs,
     * which reads as a broken screen rather than as a design decision.
     *
     * <p>The live half is bounded by instants, never by a date expression on
     * {@code occurred_at}: wrapping the column in a timezone conversion would
     * shut out the index the audit trail relies on. The requested window
     * narrows it further whenever one was given.</p>
     */
    private Merged merged(LocalDate from, LocalDate to, ZoneId zone) {
        ZoneId effective = zone == null ? ZoneId.systemDefault() : zone;
        // absent watermark: nothing is summarised, so everything is live
        LocalDate watermark = summarisedThrough().orElse(null);
        List<Object> params = new ArrayList<>();

        StringBuilder sql = new StringBuilder("(");
        if (watermark != null) {
            sql.append("SELECT activity_day, actor, action, tenant_id, event_count"
                    + " FROM user_activity_daily WHERE activity_day <= ?");
            params.add(Date.valueOf(watermark));
            sql.append(" UNION ALL ");
        }
        // first day the roll-up has not covered yet, clamped to the window
        LocalDate liveFrom = watermark == null ? from : watermark.plusDays(1);
        if (from != null && (liveFrom == null || from.isAfter(liveFrom))) {
            liveFrom = from;
        }
        sql.append("SELECT (occurred_at AT TIME ZONE CAST(? AS text))::date AS activity_day,"
                + " COALESCE(actor, 'system') AS actor, action, tenant_id, count(*) AS event_count"
                + " FROM audit_log WHERE 1=1");
        params.add(effective.getId());
        if (liveFrom != null) {
            sql.append(" AND occurred_at >= ?");
            params.add(liveFrom.atStartOfDay(effective).toInstant());
        }
        if (to != null) {
            sql.append(" AND occurred_at < ?");
            params.add(to.plusDays(1).atStartOfDay(effective).toInstant());
        }
        sql.append(" GROUP BY 1, 2, 3, 4) src");
        return new Merged(sql.toString(), params);
    }

    /**
     * Distinct action names present in the summary, for the filter dropdown.
     * The recent audit trail is unioned in so an action first used today is
     * selectable today, rather than the day after the roll-up notices it. The
     * seven-day bound keeps that half an index range scan instead of a full
     * pass over the audit trail.
     */
    public List<String> distinctActions() {
        return jdbc.query("""
                SELECT action FROM (
                    SELECT DISTINCT action FROM user_activity_daily
                    UNION
                    SELECT DISTINCT action FROM audit_log WHERE occurred_at >= ?
                ) actions ORDER BY action
                """, rs -> rs.getString(1), Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS));
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
