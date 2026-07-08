package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileAuditLoggerTest {

    @TempDir Path tempDir;

    FileAuditLogger auditLogger;
    Path auditFile;

    @BeforeEach
    void setUp() {
        auditFile = tempDir.resolve("audit.log");
        auditLogger = new FileAuditLogger();
        auditLogger.enabled = true;
        auditLogger.file = auditFile.toString();
        auditLogger.currentUser = new CurrentUser();
    }

    @Test
    void log_rbacUser_writesJsonLineWithUsername() throws Exception {
        auditLogger.currentUser.set("u1", "alice", Set.of(Permission.CONTAINERS_RUN));

        auditLogger.log("CONTAINER_CREATE", "my-app", "image=postgres:16");

        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains("\"user\":\"alice\""));
        assertTrue(line.contains("\"action\":\"CONTAINER_CREATE\""));
        assertTrue(line.contains("\"target\":\"my-app\""));
        assertTrue(line.contains("\"detail\":\"image=postgres:16\""));
        assertTrue(line.contains("\"timestamp\":\""));
    }

    @Test
    void log_withoutRbac_recordsAnonymous() throws Exception {
        auditLogger.log("CONTAINER_STOP", "abc123", null);

        String line = Files.readAllLines(auditFile).get(0);
        assertTrue(line.contains("\"user\":\"anonymous\""));
        assertFalse(line.contains("\"detail\""));
    }

    @Test
    void logAs_usesExplicitActor() throws Exception {
        auditLogger.logAs("bob", "LOGIN", "session", "ip=10.0.0.1");

        String line = Files.readAllLines(auditFile).get(0);
        assertTrue(line.contains("\"user\":\"bob\""));
        assertTrue(line.contains("\"action\":\"LOGIN\""));
    }

    @Test
    void log_escapesJsonSpecialCharacters() throws Exception {
        auditLogger.logAs("eve", "ROLE_CREATE", "quote\"back\\slash", "line\nbreak");

        String line = Files.readAllLines(auditFile).get(0);
        assertTrue(line.contains("quote\\\"back\\\\slash"));
        assertTrue(line.contains("line\\nbreak"));
    }

    @Test
    void log_appendsAcrossCalls() throws Exception {
        auditLogger.logAs("a", "LOGIN", "session", null);
        auditLogger.logAs("b", "LOGIN", "session", null);

        assertEquals(2, Files.readAllLines(auditFile).size());
    }

    @Test
    void log_disabled_writesNothing() {
        auditLogger.enabled = false;

        auditLogger.logAs("alice", "LOGIN", "session", null);

        assertFalse(Files.exists(auditFile));
    }

    @Test
    void removeEntriesOlderThan_dropsOldKeepsRecentAndUnparsable() throws Exception {
        Files.writeString(auditFile, String.join("\n",
                "{\"timestamp\":\"2020-01-01T00:00:00Z\",\"user\":\"old\",\"action\":\"LOGIN\",\"target\":\"session\"}",
                "not-a-json-line",
                "{\"timestamp\":\"2099-01-01T00:00:00Z\",\"user\":\"future\",\"action\":\"LOGIN\",\"target\":\"session\"}") + "\n");

        int removed = auditLogger.removeEntriesOlderThan(java.time.Instant.parse("2021-01-01T00:00:00Z"));

        assertEquals(1, removed);
        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(2, lines.size());
        assertEquals("not-a-json-line", lines.get(0));
        assertTrue(lines.get(1).contains("\"user\":\"future\""));
    }

    @Test
    void removeEntriesOlderThan_missingFileOrNothingToRemove_isNoOp() throws Exception {
        assertEquals(0, auditLogger.removeEntriesOlderThan(java.time.Instant.now()));

        auditLogger.logAs("alice", "LOGIN", "session", null);
        assertEquals(0, auditLogger.removeEntriesOlderThan(java.time.Instant.parse("2000-01-01T00:00:00Z")));
        assertEquals(1, Files.readAllLines(auditFile).size());
    }

    @Test
    void log_createsParentDirectories() throws Exception {
        auditLogger.file = tempDir.resolve("nested/dir/audit.log").toString();

        auditLogger.logAs("alice", "LOGIN", "session", null);

        assertEquals(1, Files.readAllLines(tempDir.resolve("nested/dir/audit.log")).size());
    }
}
