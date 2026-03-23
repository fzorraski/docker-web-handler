package br.com.fzdevx.domain.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeParserTest {

    @Test
    void parseExpiresAt_null_returnsNull() {
        assertNull(DateTimeParser.parseExpiresAt(null));
    }

    @Test
    void parseExpiresAt_blank_returnsNull() {
        assertNull(DateTimeParser.parseExpiresAt("   "));
    }

    @Test
    void parseExpiresAt_empty_returnsNull() {
        assertNull(DateTimeParser.parseExpiresAt(""));
    }

    @Test
    void parseExpiresAt_validIsoLocalDateTime_returnsInstant() {
        Instant result = DateTimeParser.parseExpiresAt("2025-12-31T23:59:00");
        assertNotNull(result);
    }

    @Test
    void parseExpiresAt_invalidFormat_returnsNull() {
        assertNull(DateTimeParser.parseExpiresAt("not-a-date"));
    }

    @Test
    void parseExpiresAt_isoWithOffset_returnsNull() {
        // ISO_LOCAL_DATE_TIME doesn't support offsets
        assertNull(DateTimeParser.parseExpiresAt("2025-12-31T23:59:00+03:00"));
    }

    @Test
    void parseExpiresAt_dateOnly_returnsNull() {
        assertNull(DateTimeParser.parseExpiresAt("2025-12-31"));
    }

    @Test
    void parseExpiresAt_withSeconds_returnsInstant() {
        Instant result = DateTimeParser.parseExpiresAt("2025-06-15T10:30:45");
        assertNotNull(result);
    }
}
