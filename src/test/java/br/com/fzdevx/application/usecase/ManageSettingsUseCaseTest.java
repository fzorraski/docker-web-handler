package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.RuntimeSettings;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.config.RuntimeSettingsService;
import br.com.fzdevx.infrastructure.persistence.JsonFileSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManageSettingsUseCaseTest {

    @TempDir Path tempDir;

    ManageSettingsUseCase useCase;
    JsonFileSettingsRepository repository;
    RuntimeSettingsService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = new JsonFileSettingsRepository(tempDir.resolve("settings.json").toString());

        service = new RuntimeSettingsService();
        setField(service, "settingsRepository", repository);
        setField(service, "terminalEnabledDefault", false);
        setField(service, "terminalMaxSessionsDefault", 5);
        setField(service, "terminalIdleTimeoutMinutesDefault", 30);
        setField(service, "terminalUploadEnabledDefault", false);
        setField(service, "terminalUploadMaxSizeMbDefault", 100);
        setField(service, "logAnalyzerEnabledDefault", false);
        setField(service, "sessionTimeoutMinutesDefault", 480);

        useCase = new ManageSettingsUseCase();
        useCase.settingsRepository = repository;
        useCase.runtimeSettingsService = service;
        CurrentUser actor = new CurrentUser();
        actor.set("u1", "root", java.util.Set.of());
        useCase.currentUser = actor;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void update_appliesPartialChangesAndTakesEffectImmediately() {
        useCase.update(Map.of("terminalEnabled", true, "terminalMaxSessions", new BigDecimal("8")));

        assertTrue(service.isTerminalEnabled());
        assertEquals(8, service.getTerminalMaxSessions());
        // persisted
        RuntimeSettings stored = repository.get();
        assertEquals(Boolean.TRUE, stored.getTerminalEnabled());
        assertEquals(8, stored.getTerminalMaxSessions());
        // untouched keys keep defaults
        assertEquals(480, service.getSessionTimeoutMinutes());
    }

    @Test
    void update_unknownKey_throws() {
        assertThrows(InvalidInputException.class,
                () -> useCase.update(Map.of("appAuthPassword", "hack")));
    }

    @Test
    void update_wrongTypes_throw() {
        assertThrows(InvalidInputException.class,
                () -> useCase.update(Map.of("terminalEnabled", "yes")));
        assertThrows(InvalidInputException.class,
                () -> useCase.update(Map.of("terminalMaxSessions", true)));
        assertThrows(InvalidInputException.class,
                () -> useCase.update(Map.of("terminalMaxSessions", new BigDecimal("2.5"))));
        assertThrows(InvalidInputException.class,
                () -> useCase.update(Map.of("terminalMaxSessions", new BigDecimal("0"))));
        assertThrows(InvalidInputException.class,
                () -> useCase.update(Map.of("sessionTimeoutMinutes", new BigDecimal("2000000"))));
    }

    @Test
    void update_emptyOrNull_throws() {
        assertThrows(InvalidInputException.class, () -> useCase.update(Map.of()));
        assertThrows(InvalidInputException.class, () -> useCase.update(null));
    }

    @Test
    void update_nullValue_clearsOverride() {
        useCase.update(Map.of("logAnalyzerEnabled", true));
        assertTrue(service.isLogAnalyzerEnabled());

        Map<String, Object> clear = new HashMap<>();
        clear.put("logAnalyzerEnabled", null);
        useCase.update(clear);
        assertFalse(service.isLogAnalyzerEnabled());
    }

    @Test
    void reset_restoresPropertyDefault() {
        useCase.update(Map.of("sessionTimeoutMinutes", new BigDecimal("60")));
        assertEquals(60, service.getSessionTimeoutMinutes());

        useCase.reset("sessionTimeoutMinutes");

        assertEquals(480, service.getSessionTimeoutMinutes());
        assertNull(repository.get().getSessionTimeoutMinutes());
    }

    @Test
    void reset_unknownKey_throws() {
        assertThrows(InvalidInputException.class, () -> useCase.reset("nope"));
    }
}
