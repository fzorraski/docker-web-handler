package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseDumpTest {

    @Test
    void constructor_setsAllFields() {
        Instant expires = Instant.now().plusSeconds(3600);
        DatabaseDump dump = new DatabaseDump("backup.sql", "mydb", "1.0", expires, 1024);

        assertNotNull(dump.getId());
        assertEquals("backup.sql", dump.getOriginalFilename());
        assertTrue(dump.getStoredFilename().endsWith(".gz"));
        assertEquals("mydb", dump.getDatabaseName());
        assertEquals("1.0", dump.getVersion());
        assertNotNull(dump.getUploadedAt());
        assertEquals(expires, dump.getExpiresAt());
        assertEquals(1024, dump.getFileSize());
    }

    @ParameterizedTest
    @CsvSource({
            "backup.sql, SQL",
            "dump.dump, CUSTOM",
            "archive.gz, COMPRESSED",
            "archive.tar.gz, COMPRESSED",
            "data.SQL, SQL",
            "BACKUP.DUMP, CUSTOM"
    })
    void detectFormat_correctlyDetects(String filename, String expectedFormat) {
        assertEquals(DatabaseDump.Format.valueOf(expectedFormat), DatabaseDump.detectFormat(filename));
    }

    @Test
    void isExpired_true_whenPastExpiry() {
        DatabaseDump dump = new DatabaseDump();
        dump.setExpiresAt(Instant.now().minusSeconds(60));
        assertTrue(dump.isExpired());
    }

    @Test
    void isExpired_false_whenBeforeExpiry() {
        DatabaseDump dump = new DatabaseDump();
        dump.setExpiresAt(Instant.now().plusSeconds(3600));
        assertFalse(dump.isExpired());
    }

    @Test
    void isExpired_false_whenNoExpiry() {
        DatabaseDump dump = new DatabaseDump();
        dump.setExpiresAt(null);
        assertFalse(dump.isExpired());
    }

    @Test
    void equals_sameId() {
        DatabaseDump a = new DatabaseDump();
        a.setId("abc");
        DatabaseDump b = new DatabaseDump();
        b.setId("abc");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentId() {
        DatabaseDump a = new DatabaseDump();
        a.setId("abc");
        DatabaseDump b = new DatabaseDump();
        b.setId("def");
        assertNotEquals(a, b);
    }
}
