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

}
