package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.RuntimeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileSettingsRepositoryTest {

    @TempDir Path tempDir;
    JsonFileSettingsRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JsonFileSettingsRepository(tempDir.resolve("settings.json").toString());
    }

    @Test
    void get_noFile_returnsEmptySettings() {
        RuntimeSettings settings = repo.get();
        assertNotNull(settings);
        assertNull(settings.getTerminalEnabled());
        assertNull(settings.getSessionTimeoutMinutes());
    }

    @Test
    void save_thenGet_roundTripsOverrides() {
        RuntimeSettings settings = new RuntimeSettings();
        settings.setTerminalEnabled(true);
        settings.setTerminalMaxSessions(10);
        repo.save(settings);

        JsonFileSettingsRepository reloaded =
                new JsonFileSettingsRepository(tempDir.resolve("settings.json").toString());
        RuntimeSettings read = reloaded.get();
        assertEquals(Boolean.TRUE, read.getTerminalEnabled());
        assertEquals(10, read.getTerminalMaxSessions());
        assertNull(read.getLogAnalyzerEnabled(), "non-overridden settings stay null");
    }

    @Test
    void save_clearingAnOverride_persistsNull() {
        RuntimeSettings settings = new RuntimeSettings();
        settings.setTerminalMaxSessions(10);
        repo.save(settings);

        settings.setTerminalMaxSessions(null);
        repo.save(settings);

        assertNull(repo.get().getTerminalMaxSessions());
    }
}
