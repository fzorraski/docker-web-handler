package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.model.RuntimeSettings;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.config.RuntimeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditRetentionServiceTest {

    @TempDir Path tempDir;

    AuditRetentionService retentionService;
    FileAuditLogger fileAuditLogger;
    RuntimeSettings stored;
    Path auditFile;

    @BeforeEach
    void setUp() throws Exception {
        stored = new RuntimeSettings();
        auditFile = tempDir.resolve("audit.log");

        fileAuditLogger = new FileAuditLogger();
        fileAuditLogger.enabled = true;
        fileAuditLogger.file = auditFile.toString();
        fileAuditLogger.currentUser = new CurrentUser();

        RuntimeSettingsService settingsService = new RuntimeSettingsService();
        SettingsRepository repository = new SettingsRepository() {
            @Override public RuntimeSettings get() { return stored; }
            @Override public void save(RuntimeSettings settings) { stored = settings; }
        };
        java.lang.reflect.Field repoField = RuntimeSettingsService.class.getDeclaredField("settingsRepository");
        repoField.setAccessible(true);
        repoField.set(settingsService, repository);

        retentionService = new AuditRetentionService();
        retentionService.runtimeSettingsService = settingsService;
        retentionService.fileAuditLogger = fileAuditLogger;
    }

    private void writeOldAndRecentEntries() throws Exception {
        Files.writeString(auditFile,
                "{\"timestamp\":\"2020-01-01T00:00:00Z\",\"user\":\"old\",\"action\":\"LOGIN\",\"target\":\"session\"}\n");
        fileAuditLogger.logAs("recent", "LOGIN", "session", null);
    }

    @Test
    void cleanupNow_removesEntriesOlderThanRetention() throws Exception {
        writeOldAndRecentEntries();
        stored.setAuditRetentionDays(90);

        retentionService.cleanupNow();

        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"user\":\"recent\""));
    }

    @Test
    void cleanupNow_retentionZero_keepsEverything() throws Exception {
        writeOldAndRecentEntries();
        stored.setAuditRetentionDays(0);

        retentionService.cleanupNow();

        assertEquals(2, Files.readAllLines(auditFile).size());
    }

    @Test
    void cleanupNow_missingFile_isSafe() {
        stored.setAuditRetentionDays(90);

        assertDoesNotThrow(retentionService::cleanupNow);
    }
}
