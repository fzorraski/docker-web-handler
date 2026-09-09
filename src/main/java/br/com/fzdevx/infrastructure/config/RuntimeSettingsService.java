package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.model.RuntimeSettings;
import br.com.fzdevx.domain.shared.InputValidator;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Layered settings lookup: a value overridden at runtime (the runtime_settings
 * row, or data/settings.json on the file backend) wins, otherwise the
 * application.properties (or env var) default applies. The
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

    @ConfigProperty(name = "container.terminal.upload.image.enabled", defaultValue = "false")
    boolean terminalImageUploadEnabledDefault;

    @ConfigProperty(name = "container.terminal.upload.image.path", defaultValue = "/tmp")
    String terminalImageUploadPathDefault;

    /**
     * How the uploaded image's path is typed into the prompt; {@code {path}} is replaced.
     * Optional because an empty property value reads as absent; the literal {@code none}
     * means "type nothing" when configured through properties or the environment.
     */
    @ConfigProperty(name = "container.terminal.upload.image.path-template")
    Optional<String> terminalImagePathTemplateDefault;

    @ConfigProperty(name = "container.terminal.upload.image.max-size-mb", defaultValue = "10")
    int terminalImageMaxSizeMbDefault;

    @ConfigProperty(name = "log.analyzer.enabled", defaultValue = "false")
    boolean logAnalyzerEnabledDefault;

    @ConfigProperty(name = "app.auth.session-timeout-minutes", defaultValue = "480")
    int sessionTimeoutMinutesDefault;

    @ConfigProperty(name = "audit.retention-days", defaultValue = "0")
    int auditRetentionDaysDefault;

    /** Restart-only gate for the retention setting; surfaced so the Settings UI can say the row is inactive. */
    @ConfigProperty(name = "audit.enabled", defaultValue = "true")
    boolean auditEnabled;

    static final String CATEGORY_TERMINAL = "terminal";
    static final String CATEGORY_LOG_ANALYZER = "logAnalyzer";
    static final String CATEGORY_SESSION = "session";
    static final String CATEGORY_AUDIT = "audit";
    static final String AUDIT_ENABLED_PROPERTY = "audit.enabled";

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

    /** Paste/drop image attachments in the terminal; independent of the generic file upload. */
    public boolean isTerminalImageUploadEnabled() {
        Boolean override = overrides().getTerminalImageUploadEnabled();
        return override != null ? override : terminalImageUploadEnabledDefault;
    }

    /** Directory inside the container where pasted/dropped images are stored. */
    public String getTerminalImageUploadPath() {
        String override = overrides().getTerminalImageUploadPath();
        return override != null && !override.isBlank() ? override : terminalImageUploadPathDefault;
    }

    /**
     * Text typed into the prompt after an image upload, with {@code {path}} standing for the
     * container path. Empty (not null) means nothing is typed: an override of "" is honored.
     */
    public String getTerminalImagePathTemplate() {
        String override = overrides().getTerminalImagePathTemplate();
        return override != null ? override : defaultImagePathTemplate();
    }

    /** The property default after the same rules the Settings tab applies; an invalid value falls back to {@code {path}}. */
    String defaultImagePathTemplate() {
        String raw = terminalImagePathTemplateDefault == null ? "{path}" : terminalImagePathTemplateDefault.orElse("{path}");
        if ("none".equalsIgnoreCase(raw.trim())) return "";
        Optional<String> error = InputValidator.validateImagePathTemplate(raw);
        if (error.isPresent()) {
            Log.warnf("container.terminal.upload.image.path-template '%s' ignored: %s Using {path}.", raw, error.get());
            return "{path}";
        }
        return raw;
    }

    /** Size cap for pasted/dropped images. Config-only (no runtime override), but read from here so the UI and the endpoint agree. */
    public int getTerminalImageMaxSizeMb() {
        return terminalImageMaxSizeMbDefault;
    }

    public boolean isLogAnalyzerEnabled() {
        Boolean override = overrides().getLogAnalyzerEnabled();
        return override != null ? override : logAnalyzerEnabledDefault;
    }

    public int getSessionTimeoutMinutes() {
        Integer override = overrides().getSessionTimeoutMinutes();
        return override != null ? override : sessionTimeoutMinutesDefault;
    }

    /** Audit entries older than this many days are deleted; 0 keeps them forever. */
    public int getAuditRetentionDays() {
        Integer override = overrides().getAuditRetentionDays();
        return override != null ? override : auditRetentionDaysDefault;
    }

    /**
     * Effective settings with metadata, for the admin Settings UI. Each entry carries its
     * {@code category} and the key of the setting it {@code dependsOn} (null for roots), so the
     * UI can group rows and nest children under the flag that gates them. Parents are listed
     * before their children. {@code disabledByProperty} names a restart-only property that
     * currently switches the setting off, or is null.
     */
    public List<Map<String, Object>> describe() {
        RuntimeSettings overrides = overrides();
        List<Map<String, Object>> settings = new ArrayList<>();
        settings.add(entry("terminalEnabled", "boolean", isTerminalEnabled(),
                terminalEnabledDefault, overrides.getTerminalEnabled() != null,
                CATEGORY_TERMINAL, null));
        settings.add(entry("terminalMaxSessions", "integer", getTerminalMaxSessions(),
                terminalMaxSessionsDefault, overrides.getTerminalMaxSessions() != null,
                CATEGORY_TERMINAL, "terminalEnabled"));
        settings.add(entry("terminalIdleTimeoutMinutes", "integer", getTerminalIdleTimeoutMinutes(),
                terminalIdleTimeoutMinutesDefault, overrides.getTerminalIdleTimeoutMinutes() != null,
                CATEGORY_TERMINAL, "terminalEnabled"));
        settings.add(entry("terminalUploadEnabled", "boolean", isTerminalUploadEnabled(),
                terminalUploadEnabledDefault, overrides.getTerminalUploadEnabled() != null,
                CATEGORY_TERMINAL, "terminalEnabled"));
        settings.add(entry("terminalUploadMaxSizeMb", "integer", getTerminalUploadMaxSizeMb(),
                terminalUploadMaxSizeMbDefault, overrides.getTerminalUploadMaxSizeMb() != null,
                CATEGORY_TERMINAL, "terminalUploadEnabled"));
        // Image attachments need the terminal, not the generic file upload (see docs/container-terminal.md).
        settings.add(entry("terminalImageUploadEnabled", "boolean", isTerminalImageUploadEnabled(),
                terminalImageUploadEnabledDefault, overrides.getTerminalImageUploadEnabled() != null,
                CATEGORY_TERMINAL, "terminalEnabled"));
        settings.add(entry("terminalImageUploadPath", "string", getTerminalImageUploadPath(),
                terminalImageUploadPathDefault, overrides.getTerminalImageUploadPath() != null,
                CATEGORY_TERMINAL, "terminalImageUploadEnabled"));
        settings.add(entry("terminalImagePathTemplate", "string", getTerminalImagePathTemplate(),
                defaultImagePathTemplate(), overrides.getTerminalImagePathTemplate() != null,
                CATEGORY_TERMINAL, "terminalImageUploadEnabled"));
        settings.add(entry("logAnalyzerEnabled", "boolean", isLogAnalyzerEnabled(),
                logAnalyzerEnabledDefault, overrides.getLogAnalyzerEnabled() != null,
                CATEGORY_LOG_ANALYZER, null));
        settings.add(entry("sessionTimeoutMinutes", "integer", getSessionTimeoutMinutes(),
                sessionTimeoutMinutesDefault, overrides.getSessionTimeoutMinutes() != null,
                CATEGORY_SESSION, null));
        Map<String, Object> retention = entry("auditRetentionDays", "integer", getAuditRetentionDays(),
                auditRetentionDaysDefault, overrides.getAuditRetentionDays() != null,
                CATEGORY_AUDIT, null);
        if (!auditEnabled) retention.put("disabledByProperty", AUDIT_ENABLED_PROPERTY);
        settings.add(retention);
        return settings;
    }

    /**
     * Synchronised on the same monitor as {@link #overrides()}: clearing the
     * field outside it is lost when it lands mid-rebuild, leaving the old
     * settings cached indefinitely.
     */
    public synchronized void invalidate() {
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
                                             Object defaultValue, boolean overridden,
                                             String category, String dependsOn) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("type", type);
        map.put("value", value);
        map.put("defaultValue", defaultValue);
        map.put("overridden", overridden);
        map.put("category", category);
        map.put("dependsOn", dependsOn);
        map.put("disabledByProperty", null);
        return map;
    }
}
