package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.model.RuntimeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSettingsServiceTest {

    RuntimeSettingsService service;
    RuntimeSettings stored;
    int repositoryReads;

    @BeforeEach
    void setUp() {
        stored = new RuntimeSettings();
        repositoryReads = 0;
        service = new RuntimeSettingsService();
        service.settingsRepository = new SettingsRepository() {
            @Override
            public RuntimeSettings get() {
                repositoryReads++;
                return stored;
            }

            @Override
            public void save(RuntimeSettings settings) {
                stored = settings;
            }
        };
        service.terminalEnabledDefault = false;
        service.terminalMaxSessionsDefault = 5;
        service.terminalIdleTimeoutMinutesDefault = 30;
        service.terminalUploadEnabledDefault = false;
        service.terminalUploadMaxSizeMbDefault = 100;
        service.logAnalyzerEnabledDefault = false;
        service.sessionTimeoutMinutesDefault = 480;
        service.auditRetentionDaysDefault = 0;
    }

    @Test
    void gettersFallBackToPropertyDefaults() {
        assertFalse(service.isTerminalEnabled());
        assertEquals(5, service.getTerminalMaxSessions());
        assertEquals(30, service.getTerminalIdleTimeoutMinutes());
        assertFalse(service.isTerminalUploadEnabled());
        assertEquals(100, service.getTerminalUploadMaxSizeMb());
        assertFalse(service.isLogAnalyzerEnabled());
        assertEquals(480, service.getSessionTimeoutMinutes());
        assertEquals(0, service.getAuditRetentionDays());
    }

    @Test
    void overridesWinOverDefaults() {
        stored.setTerminalEnabled(true);
        stored.setSessionTimeoutMinutes(60);

        assertTrue(service.isTerminalEnabled());
        assertEquals(60, service.getSessionTimeoutMinutes());
        // untouched settings keep their defaults
        assertEquals(5, service.getTerminalMaxSessions());
    }

    @Test
    void overridesAreCachedUntilInvalidated() {
        service.isTerminalEnabled();
        service.getSessionTimeoutMinutes();
        service.getTerminalMaxSessions();
        assertEquals(1, repositoryReads);

        RuntimeSettings updated = new RuntimeSettings();
        updated.setTerminalEnabled(true);
        stored = updated;
        assertFalse(service.isTerminalEnabled(), "stale cache expected before invalidate");

        service.invalidate();
        assertTrue(service.isTerminalEnabled());
        assertEquals(2, repositoryReads);
    }

    @Test
    void describe_reportsValueDefaultAndOverriddenFlag() {
        stored.setTerminalMaxSessions(12);

        List<Map<String, Object>> described = service.describe();

        assertEquals(8, described.size());
        Map<String, Object> maxSessions = described.stream()
                .filter(e -> "terminalMaxSessions".equals(e.get("key")))
                .findFirst().orElseThrow();
        assertEquals(12, maxSessions.get("value"));
        assertEquals(5, maxSessions.get("defaultValue"));
        assertEquals(true, maxSessions.get("overridden"));
        assertEquals("integer", maxSessions.get("type"));

        Map<String, Object> terminalEnabled = described.stream()
                .filter(e -> "terminalEnabled".equals(e.get("key")))
                .findFirst().orElseThrow();
        assertEquals(false, terminalEnabled.get("overridden"));
        assertEquals("boolean", terminalEnabled.get("type"));
    }
}
