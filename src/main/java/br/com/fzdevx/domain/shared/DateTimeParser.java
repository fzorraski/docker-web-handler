package br.com.fzdevx.domain.shared;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// ✦ CLEAN — extracted duplicated parseExpiresAt() from 3 classes
public final class DateTimeParser {

    private DateTimeParser() {}

    /**
     * Parses an ISO_LOCAL_DATE_TIME string into an Instant using the system default time zone.
     * Returns null if the input is null, blank, or unparseable.
     */
    public static Instant parseExpiresAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDateTime ldt = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
