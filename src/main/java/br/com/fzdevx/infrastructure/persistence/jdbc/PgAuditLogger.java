package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * Audit trail in the audit_log table, one row per event, mirrored to the
 * application log. Insert failures are logged but never propagated - an audit
 * problem must not fail the user's action (same contract as the file logger).
 */
@ApplicationScoped
@Typed(PgAuditLogger.class)
public class PgAuditLogger implements AuditLogger {

    @Inject
    @ConfigProperty(name = "audit.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    CurrentUser currentUser;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void log(String action, String target, String detail) {
        logAs(br.com.fzdevx.infrastructure.config.AuditActor.resolve(currentUser), action, target, detail);
    }

    @Override
    public void logAs(String actor, String action, String target, String detail) {
        if (!enabled) {
            return;
        }
        Log.infof("AUDIT user=%s action=%s target=%s%s",
                actor, action, target, detail == null ? "" : " detail=" + detail);
        try {
            jdbc.update("""
                    INSERT INTO audit_log (occurred_at, actor, action, target, detail)
                    VALUES (?, ?, ?, ?, ?)
                    """, Instant.now(), actor, action, target, detail);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to write audit event to the database.");
        }
    }

    @Override
    public int removeEntriesOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM audit_log WHERE occurred_at < ?", cutoff);
    }

    @Override
    public br.com.fzdevx.application.dto.AuditSearchResult search(
            br.com.fzdevx.application.dto.AuditSearchCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (criteria.actor() != null && !criteria.actor().isBlank()) {
            where.append(" AND lower(actor) = lower(?)");
            params.add(criteria.actor().trim());
        }
        if (criteria.action() != null && !criteria.action().isBlank()) {
            where.append(" AND action = ?");
            params.add(criteria.action().trim());
        }
        if (criteria.text() != null && !criteria.text().isBlank()) {
            where.append(" AND (target ILIKE ? OR detail ILIKE ?)");
            String like = "%" + escapeLike(criteria.text().trim()) + "%";
            params.add(like);
            params.add(like);
        }
        if (criteria.from() != null) {
            where.append(" AND occurred_at >= ?");
            params.add(criteria.from());
        }
        if (criteria.to() != null) {
            where.append(" AND occurred_at <= ?");
            params.add(criteria.to());
        }

        long total = jdbc.queryOne("SELECT count(*) FROM audit_log" + where,
                rs -> rs.getLong(1), params.toArray()).orElse(0L);

        java.util.List<Object> pageParams = new java.util.ArrayList<>(params);
        pageParams.add(criteria.size());
        pageParams.add((long) criteria.page() * criteria.size());
        java.util.List<br.com.fzdevx.domain.model.AuditEntry> entries = jdbc.query(
                "SELECT occurred_at, actor, action, target, detail FROM audit_log"
                        + where + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                rs -> new br.com.fzdevx.domain.model.AuditEntry(
                        JdbcSupport.instant(rs, "occurred_at"), rs.getString("actor"),
                        rs.getString("action"), rs.getString("target"), rs.getString("detail")),
                pageParams.toArray());

        return new br.com.fzdevx.application.dto.AuditSearchResult(entries, total);
    }

    @Override
    public java.util.List<String> distinctActions() {
        return jdbc.query("SELECT DISTINCT action FROM audit_log ORDER BY action", rs -> rs.getString(1));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
