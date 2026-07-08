package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.RuntimeSettings;
import br.com.fzdevx.infrastructure.config.RuntimeSettingsService;
import br.com.fzdevx.infrastructure.persistence.AuditRetentionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ManageSettingsUseCase {

    private static final int MIN_INT_VALUE = 1;
    private static final int MAX_INT_VALUE = 100_000;

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    RuntimeSettingsService runtimeSettingsService;

    @Inject
    AuditLogger auditLogger;

    @Inject
    AuditRetentionService auditRetentionService;

    public List<Map<String, Object>> describe() {
        return runtimeSettingsService.describe();
    }

    /** Applies a partial update; only the keys present in the map change. */
    public List<Map<String, Object>> update(Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new InvalidInputException("No settings provided.");
        }
        RuntimeSettings settings = settingsRepository.get();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            apply(settings, change.getKey(), change.getValue());
        }
        settingsRepository.save(settings);
        runtimeSettingsService.invalidate();
        auditLogger.log("SETTINGS_UPDATE", String.join(",", changes.keySet()), null);
        if (changes.containsKey("auditRetentionDays")) {
            auditRetentionService.cleanupNow();
        }
        return runtimeSettingsService.describe();
    }

    /** Removes an override so the application.properties default applies again. */
    public List<Map<String, Object>> reset(String key) {
        RuntimeSettings settings = settingsRepository.get();
        apply(settings, key, null);
        settingsRepository.save(settings);
        runtimeSettingsService.invalidate();
        auditLogger.log("SETTINGS_RESET", key, null);
        return runtimeSettingsService.describe();
    }

    private void apply(RuntimeSettings settings, String key, Object value) {
        switch (key) {
            case "terminalEnabled" -> settings.setTerminalEnabled(asBoolean(key, value));
            case "terminalMaxSessions" -> settings.setTerminalMaxSessions(asInteger(key, value));
            case "terminalIdleTimeoutMinutes" -> settings.setTerminalIdleTimeoutMinutes(asInteger(key, value));
            case "terminalUploadEnabled" -> settings.setTerminalUploadEnabled(asBoolean(key, value));
            case "terminalUploadMaxSizeMb" -> settings.setTerminalUploadMaxSizeMb(asInteger(key, value));
            case "logAnalyzerEnabled" -> settings.setLogAnalyzerEnabled(asBoolean(key, value));
            case "sessionTimeoutMinutes" -> settings.setSessionTimeoutMinutes(asInteger(key, value));
            // 0 = keep audit entries forever
            case "auditRetentionDays" -> settings.setAuditRetentionDays(asInteger(key, value, 0));
            default -> throw new InvalidInputException("Unknown setting: " + key);
        }
    }

    private Boolean asBoolean(String key, Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        throw new InvalidInputException("Setting '" + key + "' expects a boolean value.");
    }

    private Integer asInteger(String key, Object value) {
        return asInteger(key, value, MIN_INT_VALUE);
    }

    private Integer asInteger(String key, Object value, int minValue) {
        if (value == null) return null;
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            int intValue = number.intValue();
            if (raw != intValue || intValue < minValue || intValue > MAX_INT_VALUE) {
                throw new InvalidInputException("Setting '" + key + "' expects an integer between "
                        + minValue + " and " + MAX_INT_VALUE + ".");
            }
            return intValue;
        }
        throw new InvalidInputException("Setting '" + key + "' expects an integer value.");
    }
}
