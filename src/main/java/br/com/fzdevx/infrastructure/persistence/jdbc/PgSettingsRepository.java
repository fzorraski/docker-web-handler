package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.model.RuntimeSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Runtime setting overrides in the single-row runtime_settings table. */
@ApplicationScoped
@Typed(PgSettingsRepository.class)
public class PgSettingsRepository implements SettingsRepository {

    @Inject
    JdbcSupport jdbc;

    @Override
    public RuntimeSettings get() {
        return jdbc.queryOne("""
                SELECT terminal_enabled, terminal_max_sessions, terminal_idle_timeout_minutes,
                       terminal_upload_enabled, terminal_upload_max_size_mb, log_analyzer_enabled,
                       session_timeout_minutes, audit_retention_days
                FROM runtime_settings WHERE id = 1
                """, PgSettingsRepository::map)
                .orElseGet(RuntimeSettings::new);
    }

    @Override
    public void save(RuntimeSettings settings) {
        jdbc.update("""
                INSERT INTO runtime_settings (id, terminal_enabled, terminal_max_sessions,
                    terminal_idle_timeout_minutes, terminal_upload_enabled, terminal_upload_max_size_mb,
                    log_analyzer_enabled, session_timeout_minutes, audit_retention_days, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    terminal_enabled = EXCLUDED.terminal_enabled,
                    terminal_max_sessions = EXCLUDED.terminal_max_sessions,
                    terminal_idle_timeout_minutes = EXCLUDED.terminal_idle_timeout_minutes,
                    terminal_upload_enabled = EXCLUDED.terminal_upload_enabled,
                    terminal_upload_max_size_mb = EXCLUDED.terminal_upload_max_size_mb,
                    log_analyzer_enabled = EXCLUDED.log_analyzer_enabled,
                    session_timeout_minutes = EXCLUDED.session_timeout_minutes,
                    audit_retention_days = EXCLUDED.audit_retention_days,
                    updated_at = EXCLUDED.updated_at
                """,
                settings.getTerminalEnabled(), settings.getTerminalMaxSessions(),
                settings.getTerminalIdleTimeoutMinutes(), settings.getTerminalUploadEnabled(),
                settings.getTerminalUploadMaxSizeMb(), settings.getLogAnalyzerEnabled(),
                settings.getSessionTimeoutMinutes(), settings.getAuditRetentionDays(),
                Instant.now());
    }

    private static RuntimeSettings map(ResultSet rs) throws SQLException {
        RuntimeSettings settings = new RuntimeSettings();
        settings.setTerminalEnabled(rs.getObject("terminal_enabled", Boolean.class));
        settings.setTerminalMaxSessions(rs.getObject("terminal_max_sessions", Integer.class));
        settings.setTerminalIdleTimeoutMinutes(rs.getObject("terminal_idle_timeout_minutes", Integer.class));
        settings.setTerminalUploadEnabled(rs.getObject("terminal_upload_enabled", Boolean.class));
        settings.setTerminalUploadMaxSizeMb(rs.getObject("terminal_upload_max_size_mb", Integer.class));
        settings.setLogAnalyzerEnabled(rs.getObject("log_analyzer_enabled", Boolean.class));
        settings.setSessionTimeoutMinutes(rs.getObject("session_timeout_minutes", Integer.class));
        settings.setAuditRetentionDays(rs.getObject("audit_retention_days", Integer.class));
        return settings;
    }
}
