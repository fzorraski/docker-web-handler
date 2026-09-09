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
        service.terminalImageUploadEnabledDefault = false;
        service.terminalImageUploadPathDefault = "/tmp";
        service.terminalImagePathTemplateDefault = java.util.Optional.of("{path}");
        service.terminalImageMaxSizeMbDefault = 10;
        service.logAnalyzerEnabledDefault = false;
        service.sessionTimeoutMinutesDefault = 480;
        service.auditRetentionDaysDefault = 0;
        service.auditEnabled = true;
    }

    @Test
    void gettersFallBackToPropertyDefaults() {
        assertFalse(service.isTerminalEnabled());
        assertEquals(5, service.getTerminalMaxSessions());
        assertEquals(30, service.getTerminalIdleTimeoutMinutes());
        assertFalse(service.isTerminalUploadEnabled());
        assertEquals(100, service.getTerminalUploadMaxSizeMb());
        assertFalse(service.isTerminalImageUploadEnabled());
        assertEquals("/tmp", service.getTerminalImageUploadPath());
        assertEquals("{path}", service.getTerminalImagePathTemplate());
        assertEquals(10, service.getTerminalImageMaxSizeMb());
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
    void imagePathTemplateDefault_absentNoneAndInvalidValues() {
        service.terminalImagePathTemplateDefault = java.util.Optional.empty();
        assertEquals("{path}", service.getTerminalImagePathTemplate(), "absent property keeps the standard template");

        service.terminalImagePathTemplateDefault = java.util.Optional.of(" NONE ");
        assertEquals("", service.getTerminalImagePathTemplate(), "'none' means type nothing");

        service.terminalImagePathTemplateDefault = java.util.Optional.of("image: ");
        assertEquals("{path}", service.getTerminalImagePathTemplate(), "a default without the placeholder is ignored");
        assertEquals("{path}", service.describe().stream()
                .filter(e -> "terminalImagePathTemplate".equals(e.get("key"))).findFirst().orElseThrow().get("defaultValue"));
    }

    @Test
    void emptyImagePathTemplateOverride_isHonoredNotDefaulted() {
        stored.setTerminalImagePathTemplate("");
        assertEquals("", service.getTerminalImagePathTemplate());
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

        assertEquals(11, described.size());
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

    @Test
    void describe_reportsCategoryAndParentPerKey() {
        List<Map<String, Object>> described = service.describe();
        Map<String, Map<String, Object>> byKey = new java.util.LinkedHashMap<>();
        described.forEach(e -> byKey.put((String) e.get("key"), e));

        Map<String, String> expectedParent = new java.util.HashMap<>();
        expectedParent.put("terminalEnabled", null);
        expectedParent.put("terminalMaxSessions", "terminalEnabled");
        expectedParent.put("terminalIdleTimeoutMinutes", "terminalEnabled");
        expectedParent.put("terminalUploadEnabled", "terminalEnabled");
        expectedParent.put("terminalUploadMaxSizeMb", "terminalUploadEnabled");
        expectedParent.put("terminalImageUploadEnabled", "terminalEnabled");
        expectedParent.put("terminalImageUploadPath", "terminalImageUploadEnabled");
        expectedParent.put("terminalImagePathTemplate", "terminalImageUploadEnabled");
        expectedParent.put("logAnalyzerEnabled", null);
        expectedParent.put("sessionTimeoutMinutes", null);
        expectedParent.put("auditRetentionDays", null);
        assertEquals(expectedParent.keySet(), byKey.keySet());

        Map<String, String> expectedCategory = Map.ofEntries(
                Map.entry("terminalEnabled", "terminal"),
                Map.entry("terminalMaxSessions", "terminal"),
                Map.entry("terminalIdleTimeoutMinutes", "terminal"),
                Map.entry("terminalUploadEnabled", "terminal"),
                Map.entry("terminalUploadMaxSizeMb", "terminal"),
                Map.entry("terminalImageUploadEnabled", "terminal"),
                Map.entry("terminalImageUploadPath", "terminal"),
                Map.entry("terminalImagePathTemplate", "terminal"),
                Map.entry("logAnalyzerEnabled", "logAnalyzer"),
                Map.entry("sessionTimeoutMinutes", "session"),
                Map.entry("auditRetentionDays", "audit"));

        List<String> order = new java.util.ArrayList<>(byKey.keySet());
        byKey.forEach((key, entry) -> {
            assertEquals(expectedParent.get(key), entry.get("dependsOn"), key);
            assertEquals(expectedCategory.get(key), entry.get("category"), key);
            assertNull(entry.get("disabledByProperty"), key);
            String parent = (String) entry.get("dependsOn");
            if (parent != null) {
                assertTrue(order.indexOf(parent) < order.indexOf(key), key + " must follow its parent " + parent);
            }
        });
    }

    @Test
    void describe_flagsAuditRetentionWhileAuditIsDisabled() {
        service.auditEnabled = false;

        List<Map<String, Object>> described = service.describe();

        assertEquals(11, described.size());
        described.forEach(e -> {
            Object gate = e.get("disabledByProperty");
            if ("auditRetentionDays".equals(e.get("key"))) assertEquals("audit.enabled", gate);
            else assertNull(gate, (String) e.get("key"));
        });
    }
}
