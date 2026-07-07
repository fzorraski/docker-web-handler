package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.model.RuntimeSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layered settings lookup: a value overridden in data/settings.json wins,
 * otherwise the application.properties (or env var) default applies. The
 * overrides snapshot is cached; {@link #invalidate()} is called after every
 * settings write so changes apply without restart.
 */
@ApplicationScoped
public class RuntimeSettingsService {

    @Inject
    SettingsRepository settingsRepository;

    @ConfigProperty(name = "container.terminal.enabled", defaultValue = "false")
    boolean terminalEnabledDefault;

    @ConfigProperty(name = "container.terminal.max-sessions", defaultValue = "5")
    int terminalMaxSessionsDefault;

    @ConfigProperty(name = "container.terminal.idle-timeout-minutes", defaultValue = "30")
    int terminalIdleTimeoutMinutesDefault;

    @ConfigProperty(name = "container.terminal.upload.enabled", defaultValue = "false")
    boolean terminalUploadEnabledDefault;

    @ConfigProperty(name = "container.terminal.upload.max-size-mb", defaultValue = "100")
    int terminalUploadMaxSizeMbDefault;

    @ConfigProperty(name = "log.analyzer.enabled", defaultValue = "false")
    boolean logAnalyzerEnabledDefault;

    @ConfigProperty(name = "app.auth.session-timeout-minutes", defaultValue = "480")
    int sessionTimeoutMinutesDefault;

    private volatile RuntimeSettings cached;

    public boolean isTerminalEnabled() {
        Boolean override = overrides().getTerminalEnabled();
        return override != null ? override : terminalEnabledDefault;
    }

    public int getTerminalMaxSessions() {
        Integer override = overrides().getTerminalMaxSessions();
        return override != null ? override : terminalMaxSessionsDefault;
    }

    public int getTerminalIdleTimeoutMinutes() {
        Integer override = overrides().getTerminalIdleTimeoutMinutes();
        return override != null ? override : terminalIdleTimeoutMinutesDefault;
    }

    public boolean isTerminalUploadEnabled() {
        Boolean override = overrides().getTerminalUploadEnabled();
        return override != null ? override : terminalUploadEnabledDefault;
    }

    public int getTerminalUploadMaxSizeMb() {
        Integer override = overrides().getTerminalUploadMaxSizeMb();
        return override != null ? override : terminalUploadMaxSizeMbDefault;
    }

    public boolean isLogAnalyzerEnabled() {
        Boolean override = overrides().getLogAnalyzerEnabled();
        return override != null ? override : logAnalyzerEnabledDefault;
    }

    public int getSessionTimeoutMinutes() {
        Integer override = overrides().getSessionTimeoutMinutes();
        return override != null ? override : sessionTimeoutMinutesDefault;
    }

    /** Effective settings with metadata, for the admin Settings UI. */
    public List<Map<String, Object>> describe() {
        RuntimeSettings overrides = overrides();
        List<Map<String, Object>> settings = new ArrayList<>();
        settings.add(entry("terminalEnabled", "boolean", isTerminalEnabled(),
                terminalEnabledDefault, overrides.getTerminalEnabled() != null));
        settings.add(entry("terminalMaxSessions", "integer", getTerminalMaxSessions(),
                terminalMaxSessionsDefault, overrides.getTerminalMaxSessions() != null));
        settings.add(entry("terminalIdleTimeoutMinutes", "integer", getTerminalIdleTimeoutMinutes(),
                terminalIdleTimeoutMinutesDefault, overrides.getTerminalIdleTimeoutMinutes() != null));
        settings.add(entry("terminalUploadEnabled", "boolean", isTerminalUploadEnabled(),
                terminalUploadEnabledDefault, overrides.getTerminalUploadEnabled() != null));
        settings.add(entry("terminalUploadMaxSizeMb", "integer", getTerminalUploadMaxSizeMb(),
                terminalUploadMaxSizeMbDefault, overrides.getTerminalUploadMaxSizeMb() != null));
        settings.add(entry("logAnalyzerEnabled", "boolean", isLogAnalyzerEnabled(),
                logAnalyzerEnabledDefault, overrides.getLogAnalyzerEnabled() != null));
        settings.add(entry("sessionTimeoutMinutes", "integer", getSessionTimeoutMinutes(),
                sessionTimeoutMinutesDefault, overrides.getSessionTimeoutMinutes() != null));
        return settings;
    }

    public void invalidate() {
        cached = null;
    }

    private RuntimeSettings overrides() {
        RuntimeSettings snapshot = cached;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = cached;
                if (snapshot == null) {
                    snapshot = settingsRepository.get();
                    cached = snapshot;
                }
            }
        }
        return snapshot;
    }

    private static Map<String, Object> entry(String key, String type, Object value,
                                             Object defaultValue, boolean overridden) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("type", type);
        map.put("value", value);
        map.put("defaultValue", defaultValue);
        map.put("overridden", overridden);
        return map;
    }
}
