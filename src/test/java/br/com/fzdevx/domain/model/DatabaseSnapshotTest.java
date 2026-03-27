package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSnapshotTest {

    @Test
    void constructor_setsAllFields() {
        DatabaseSnapshot s = new DatabaseSnapshot("pg", "mydb", DatabaseSnapshot.Format.CUSTOM, "label");

        assertNotNull(s.getId());
        assertTrue(s.getStoredFilename().endsWith(".gz"));
        assertEquals("pg", s.getRepository());
        assertEquals("mydb", s.getSourceDatabaseName());
        assertEquals(DatabaseSnapshot.Format.CUSTOM, s.getFormat());
        assertEquals("label", s.getLabel());
        assertNotNull(s.getCreatedAt());
    }

    @Test
    void isExpired_true_whenPastExpiry() {
        DatabaseSnapshot s = new DatabaseSnapshot();
        s.setExpiresAt(Instant.now().minusSeconds(60));
        assertTrue(s.isExpired());
    }

    @Test
    void isExpired_false_whenBeforeExpiry() {
        DatabaseSnapshot s = new DatabaseSnapshot();
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        assertFalse(s.isExpired());
    }

    @Test
    void isExpired_false_whenNoExpiry() {
        DatabaseSnapshot s = new DatabaseSnapshot();
        s.setExpiresAt(null);
        assertFalse(s.isExpired());
    }

    @Test
    void equals_sameId() {
        DatabaseSnapshot a = new DatabaseSnapshot();
        a.setId("abc");
        DatabaseSnapshot b = new DatabaseSnapshot();
        b.setId("abc");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentId() {
        DatabaseSnapshot a = new DatabaseSnapshot();
        a.setId("abc");
        DatabaseSnapshot b = new DatabaseSnapshot();
        b.setId("def");
        assertNotEquals(a, b);
    }

    @Test
    void setters_work() {
        DatabaseSnapshot s = new DatabaseSnapshot();
        s.setContainerName("my-pg");
        s.setDescription("test snapshot");
        s.setMd5Hash("abc123");
        s.setFileSize(2048);

        assertEquals("my-pg", s.getContainerName());
        assertEquals("test snapshot", s.getDescription());
        assertEquals("abc123", s.getMd5Hash());
        assertEquals(2048, s.getFileSize());
    }

    @Test
    void temporary_defaultsFalse() {
        DatabaseSnapshot s = new DatabaseSnapshot();
        assertFalse(s.isTemporary());
    }

    @Test
    void temporary_setAndGet() {
        DatabaseSnapshot s = new DatabaseSnapshot();
        s.setTemporary(true);
        assertTrue(s.isTemporary());
    }
}
