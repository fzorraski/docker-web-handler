package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.RunContainerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunContainerRequestTest {

    @Test
    void copy_copiesAllFields() {
        RunContainerRequest req = new RunContainerRequest();
        req.setRepository("postgres");
        req.setTag("16");
        req.setContainerName("my-pg");
        req.setEnvVars(List.of("A=1", "B=2"));
        req.setExpiresAt("2025-12-31T23:59:00");
        req.setMemoryMb(512L);
        req.setDatabaseName("mydb");
        req.setDeleteDatabaseOnExpiration(true);
        req.setDumpId("dump-123");
        req.setCreateDatabase(true);
        req.setSelectedOptionalScripts(List.of("init.sql"));
        req.setSnapshotId("snap-123");
        req.setWebhookNotify(true);
        req.setOperationsPassword("secret");
        req.setOperationsPasswordValidated(true);
        req.setMigrationMode("API");
        req.setMigrationSql("ALTER TABLE...");
        req.setMigrationSourceVersion("1.0");
        req.setMigrationTargetVersion("2.0");

        RunContainerConfig copy = req.copy();

        assertEquals("postgres", copy.getRepository());
        assertEquals("16", copy.getTag());
        assertEquals("my-pg", copy.getContainerName());
        assertEquals(List.of("A=1", "B=2"), copy.getEnvVars());
        assertEquals("2025-12-31T23:59:00", copy.getExpiresAt());
        assertEquals(512L, copy.getMemoryMb());
        assertEquals("mydb", copy.getDatabaseName());
        assertTrue(copy.isDeleteDatabaseOnExpiration());
        assertEquals("dump-123", copy.getDumpId());
        assertTrue(copy.isCreateDatabase());
        assertEquals(List.of("init.sql"), copy.getSelectedOptionalScripts());
        assertEquals("snap-123", copy.getSnapshotId());
        assertTrue(copy.isWebhookNotify());
        assertEquals("secret", copy.getOperationsPassword());
        assertTrue(copy.isOperationsPasswordValidated());
        assertEquals("API", copy.getMigrationMode());
        assertEquals("ALTER TABLE...", copy.getMigrationSql());
        assertEquals("1.0", copy.getMigrationSourceVersion());
        assertEquals("2.0", copy.getMigrationTargetVersion());
    }

    @Test
    void copy_isIndependent() {
        RunContainerRequest req = new RunContainerRequest();
        req.setRepository("postgres");
        req.setEnvVars(List.of("A=1"));

        RunContainerConfig copy = req.copy();
        req.setRepository("redis");

        assertEquals("postgres", copy.getRepository());
    }

    @Test
    void copy_nullEnvVars_staysNull() {
        RunContainerRequest req = new RunContainerRequest();
        req.setEnvVars(null);

        RunContainerConfig copy = req.copy();
        assertNull(copy.getEnvVars());
    }

    @Test
    void copy_nullScripts_staysNull() {
        RunContainerRequest req = new RunContainerRequest();
        req.setSelectedOptionalScripts(null);

        RunContainerConfig copy = req.copy();
        assertNull(copy.getSelectedOptionalScripts());
    }
}
