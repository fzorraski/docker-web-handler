package br.com.fzdevx.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class AuthSessionManagerTest {

    private AuthSessionManager manager;
    private RuntimeSettingsService runtimeSettings;

    @BeforeEach
    void setUp() {
        manager = new AuthSessionManager();
        runtimeSettings = new RuntimeSettingsService();
        runtimeSettings.sessionTimeoutMinutesDefault = 480;
        runtimeSettings.settingsRepository = new br.com.fzdevx.application.port.SettingsRepository() {
            @Override
            public br.com.fzdevx.domain.model.RuntimeSettings get() {
                return new br.com.fzdevx.domain.model.RuntimeSettings();
            }

            @Override
            public void save(br.com.fzdevx.domain.model.RuntimeSettings settings) {
                // no-op
            }
        };
        setField("authEnabled", true);
        setField("runtimeSettings", runtimeSettings);
        setField("sessionRepository", new br.com.fzdevx.infrastructure.persistence.InMemorySessionRepository());
        setField("touchIntervalSeconds", 60L);
    }

    private void setField(String name, Object value) {
        try {
            Field f = AuthSessionManager.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(manager, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- createSession ----

    @Test
    void createSession_returnsNonNullId() {
        String id = manager.createSession();
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    @Test
    void createSession_returnsUniqueIds() {
        String id1 = manager.createSession();
        String id2 = manager.createSession();
        assertNotEquals(id1, id2);
    }

    // ---- validateAndTouch ----

    @Test
    void validateAndTouch_validSession_returnsTrue() {
        String id = manager.createSession();
        assertTrue(manager.validateAndTouch(id));
    }

    @Test
    void validateAndTouch_unknownSession_returnsFalse() {
        assertFalse(manager.validateAndTouch("nonexistent"));
    }

    @Test
    void validateAndTouch_null_returnsFalse() {
        assertFalse(manager.validateAndTouch(null));
    }

    @Test
    void validateAndTouch_blank_returnsFalse() {
        assertFalse(manager.validateAndTouch("   "));
    }

    @Test
    void validateAndTouch_empty_returnsFalse() {
        assertFalse(manager.validateAndTouch(""));
    }

    @Test
    void validateAndTouch_expiredSession_returnsFalse() {
        // Set timeout to 0 so session expires immediately
        runtimeSettings.sessionTimeoutMinutesDefault = 0;
        String id = manager.createSession();
        assertFalse(manager.validateAndTouch(id));
    }

    @Test
    void validateAndTouch_touchesSession() {
        String id = manager.createSession();
        // Multiple touches should succeed
        assertTrue(manager.validateAndTouch(id));
        assertTrue(manager.validateAndTouch(id));
        assertTrue(manager.validateAndTouch(id));
    }

    // ---- invalidateSession ----

    @Test
    void invalidateSession_removesSession() {
        String id = manager.createSession();
        assertTrue(manager.validateAndTouch(id));
        manager.invalidateSession(id);
        assertFalse(manager.validateAndTouch(id));
    }

    @Test
    void invalidateSession_null_doesNotThrow() {
        assertDoesNotThrow(() -> manager.invalidateSession(null));
    }

    @Test
    void invalidateSession_unknown_doesNotThrow() {
        assertDoesNotThrow(() -> manager.invalidateSession("nonexistent"));
    }

    // ---- isAuthEnabled ----

    @Test
    void isAuthEnabled_returnsConfigured() {
        assertTrue(manager.isAuthEnabled());
    }

    // ---- getSessionTimeoutMinutes ----

    @Test
    void getSessionTimeoutMinutes_returnsConfigured() {
        assertEquals(480, manager.getSessionTimeoutMinutes());
    }

    // ---- concurrent sessions ----

    @Test
    void multipleSessions_independent() {
        String id1 = manager.createSession();
        String id2 = manager.createSession();

        manager.invalidateSession(id1);

        assertFalse(manager.validateAndTouch(id1));
        assertTrue(manager.validateAndTouch(id2));
    }
}
