package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.CustomFieldMatch;
import br.com.fzdevx.domain.model.CustomFieldResult;
import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.model.LogPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomFieldExtractorTest {

    private CustomFieldExtractor extractor;

    private static final int DEFAULT_MAX_MATCHES = 10_000;

    @BeforeEach
    void setUp() {
        extractor = new CustomFieldExtractor();
        try {
            var f = CustomFieldExtractor.class.getDeclaredField("maxMatches");
            f.setAccessible(true);
            f.set(extractor, DEFAULT_MAX_MATCHES);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private LogLine line(int num, String message) {
        return new LogLine(num, LocalDateTime.of(2026, 3, 30, 10, 0, 0).plusSeconds(num), "INFO",
                "test.Logger", "thread-1", message, "test.log");
    }

    // ---- Basic matching ----

    @Test
    void extractsMatchesWithNamedGroups() {
        var lines = List.of(
                line(1, "Updated -> User: [name=admin] changed to User: [name=root]"),
                line(2, "Some other log line"),
                line(3, "Updated -> Config: [key=timeout] changed to Config: [key=timeout]")
        );
        var fields = List.of(
                new LogPreset.CustomField("Entity Changes", "Updated -> (?<entity>\\w+):", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.size());
        CustomFieldResult result = results.getFirst();
        assertEquals("Entity Changes", result.fieldName());
        assertEquals(2, result.matchCount());
        assertFalse(result.countOnly());
        assertEquals(2, result.matches().size());

        assertEquals("User", result.matches().get(0).groups().get("entity"));
        assertEquals(1, result.matches().get(0).lineNumber());
        assertEquals("Config", result.matches().get(1).groups().get("entity"));
    }

    // ---- Count only ----

    @Test
    void countOnlyStoresNoMatches() {
        var lines = List.of(line(1, "Updated -> User: data"), line(2, "Updated -> Config: data"));
        var fields = List.of(
                new LogPreset.CustomField("Changes", "Updated ->", true)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.size());
        assertEquals(2, results.getFirst().matchCount());
        assertTrue(results.getFirst().countOnly());
        assertTrue(results.getFirst().matches().isEmpty());
    }

    // ---- Multiple custom fields ----

    @Test
    void multipleCustomFieldsProcessedIndependently() {
        var lines = List.of(
                line(1, "Updated -> User: data"),
                line(2, "SQL query executed in 150ms"),
                line(3, "Updated -> Config: data")
        );
        var fields = List.of(
                new LogPreset.CustomField("Entity Changes", "Updated -> (?<entity>\\w+):", false),
                new LogPreset.CustomField("Slow SQL", "executed in (?<duration>\\d+)ms", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(2, results.size());
        assertEquals("Entity Changes", results.get(0).fieldName());
        assertEquals(2, results.get(0).matchCount());
        assertEquals("Slow SQL", results.get(1).fieldName());
        assertEquals(1, results.get(1).matchCount());
        assertEquals("150", results.get(1).matches().getFirst().groups().get("duration"));
    }

    // ---- No matches ----

    @Test
    void noMatchesProducesZeroCount() {
        var lines = List.of(line(1, "Normal log message"));
        var fields = List.of(
                new LogPreset.CustomField("Errors", "CRITICAL_ERROR (?<code>\\d+)", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.size());
        assertEquals(0, results.getFirst().matchCount());
        assertTrue(results.getFirst().matches().isEmpty());
    }

    // ---- Null/empty custom fields ----

    @Test
    void nullCustomFieldsReturnsEmpty() {
        List<CustomFieldResult> results = extractor.extract(List.of(line(1, "msg")), null);
        assertTrue(results.isEmpty());
    }

    @Test
    void emptyCustomFieldsReturnsEmpty() {
        List<CustomFieldResult> results = extractor.extract(List.of(line(1, "msg")), List.of());
        assertTrue(results.isEmpty());
    }

    // ---- Null messages skipped ----

    @Test
    void nullMessagesSkipped() {
        var lines = List.of(
                new LogLine(1, null, null, null, null, null, "test.log"),
                line(2, "Updated -> User: data")
        );
        var fields = List.of(
                new LogPreset.CustomField("Changes", "Updated -> (?<entity>\\w+):", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.getFirst().matchCount());
    }

    // ---- Invalid name skipped ----

    @Test
    void invalidNameSkipped() {
        var lines = List.of(line(1, "test message"));
        var fields = List.of(
                new LogPreset.CustomField("Valid-Name", "test", false),
                new LogPreset.CustomField("Invalid/Name!", "test", false),
                new LogPreset.CustomField("", "test", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.size());
        assertEquals("Valid-Name", results.getFirst().fieldName());
    }

    // ---- Invalid regex skipped ----

    @Test
    void invalidRegexSkipped() {
        var lines = List.of(line(1, "test"));
        var fields = List.of(
                new LogPreset.CustomField("Good", "test", false),
                new LogPreset.CustomField("Bad Regex", "[invalid(", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.size());
        assertEquals("Good", results.getFirst().fieldName());
    }

    // ---- Blank regex skipped ----

    @Test
    void blankRegexSkipped() {
        var lines = List.of(line(1, "test"));
        var fields = List.of(
                new LogPreset.CustomField("Blank", "   ", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertTrue(results.isEmpty());
    }

    // ---- Match cap ----

    @Test
    void matchesCappedAtMax() {
        var lines = new java.util.ArrayList<LogLine>();
        for (int i = 0; i < DEFAULT_MAX_MATCHES + 100; i++) {
            lines.add(line(i + 1, "match line " + i));
        }
        var fields = List.of(
                new LogPreset.CustomField("All Lines", "match line (?<num>\\d+)", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.size());
        assertEquals(DEFAULT_MAX_MATCHES + 100, results.getFirst().matchCount());
        assertEquals(DEFAULT_MAX_MATCHES, results.getFirst().matches().size());
    }

    // ---- Multiple named groups ----

    @Test
    void multipleNamedGroupsExtracted() {
        var lines = List.of(
                line(1, "User admin changed password for user root on server prod-01")
        );
        var fields = List.of(
                new LogPreset.CustomField("User Actions",
                        "User (?<actor>\\w+) changed (?<action>\\w+) for user (?<target>\\w+)", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        CustomFieldMatch match = results.getFirst().matches().getFirst();
        assertEquals("admin", match.groups().get("actor"));
        assertEquals("password", match.groups().get("action"));
        assertEquals("root", match.groups().get("target"));
    }

    // ---- Match preserves metadata ----

    @Test
    void matchPreservesLineMetadata() {
        var line = new LogLine(42, LocalDateTime.of(2026, 3, 30, 15, 30, 0),
                "WARN", "my.Logger", "http-thread-5", "Updated -> Config: data", "server.log");
        var fields = List.of(
                new LogPreset.CustomField("Changes", "Updated -> (?<entity>\\w+):", false)
        );

        List<CustomFieldResult> results = extractor.extract(List.of(line), fields);

        CustomFieldMatch match = results.getFirst().matches().getFirst();
        assertEquals(42, match.lineNumber());
        assertEquals(LocalDateTime.of(2026, 3, 30, 15, 30, 0), match.timestamp());
        assertEquals("http-thread-5", match.thread());
        assertEquals("server.log", match.sourceFile());
        assertEquals("Updated -> Config: data", match.fullMessage());
    }

    // ---- Regex without named groups ----

    @Test
    void regexWithoutNamedGroupsProducesEmptyGroups() {
        var lines = List.of(line(1, "ERROR: something failed"));
        var fields = List.of(
                new LogPreset.CustomField("Errors", "ERROR:", false)
        );

        List<CustomFieldResult> results = extractor.extract(lines, fields);

        assertEquals(1, results.getFirst().matchCount());
        assertTrue(results.getFirst().matches().getFirst().groups().isEmpty());
    }
}
