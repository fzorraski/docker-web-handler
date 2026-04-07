package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.model.LogPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.*;

class LogFileParserEdgeCasesTest {

    private LogFileParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new LogFileParser();
        setField("maxStoredLines", 500_000);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = LogFileParser.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(parser, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path writeLog(String... lines) throws IOException {
        return writeLog(tempDir.resolve("test-" + System.nanoTime() + ".log"), lines);
    }

    private Path writeLog(Path path, String... lines) throws IOException {
        Files.writeString(path, String.join("\n", lines) + "\n");
        return path;
    }

    // ---- Sensitive field redaction ----

    @Test
    void redactsTokenFieldInPayload() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) OrderWS/getOrder Request = {\"token\":\"secret123\",\"id\":1}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) OrderWS/getOrder Response = {\"status\":\"ok\"}"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        String requestPayload = result.getApiCalls().getFirst().requestPayload();
        assertTrue(requestPayload.contains("\"token\":\"***\""), "token should be redacted: " + requestPayload);
        assertFalse(requestPayload.contains("secret123"));
    }

    @Test
    void redactsPasswordFieldInPayload() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) UserWS/login Request = {\"user\":\"admin\",\"password\":\"p@ss\"}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) UserWS/login Response = {\"ok\":true}"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        String requestPayload = result.getApiCalls().getFirst().requestPayload();
        assertTrue(requestPayload.contains("\"password\":\"***\""), "password should be redacted: " + requestPayload);
        assertFalse(requestPayload.contains("p@ss"));
    }

    @Test
    void redactsMultipleSensitiveFieldsInSamePayload() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) AuthWS/auth Request = {\"token\":\"abc\",\"senha\":\"xyz\",\"name\":\"John\"}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) AuthWS/auth Response = {\"ok\":true}"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        String payload = result.getApiCalls().getFirst().requestPayload();
        assertTrue(payload.contains("\"token\":\"***\""));
        assertTrue(payload.contains("\"senha\":\"***\""));
        assertTrue(payload.contains("\"name\":\"John\""), "non-sensitive field should be preserved");
    }

    @Test
    void redactsResponsePayloadToo() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) TokenWS/refresh Request = {\"id\":1}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) TokenWS/refresh Response = {\"token\":\"new-secret-value\"}"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        String responsePayload = result.getApiCalls().getFirst().responsePayload();
        assertTrue(responsePayload.contains("\"token\":\"***\""));
        assertFalse(responsePayload.contains("new-secret-value"));
    }

    @Test
    void noRedactionWhenNoSensitiveFields() throws IOException {
        LogPreset presetNoSensitive = new LogPreset(
                "NoSensitive",
                LogPreset.WILDFLY.logLineRegex(),
                LogPreset.WILDFLY.timestampFormat(),
                LogPreset.WILDFLY.apiCallRegex(),
                null, null, null,
                List.of(),
                List.of(),
                List.of()
        );

        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) OrderWS/get Request = {\"token\":\"visible\"}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) OrderWS/get Response = ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), presetNoSensitive, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().getFirst().requestPayload().contains("visible"));
    }

    // ---- AnalysisOptions filtering ----

    @Test
    void apiCallsDisabledSkipsPairing() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) OrderWS/get Request = {}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) OrderWS/get Response = ok"
        );

        AnalysisOptions noApiCalls = new AnalysisOptions(false, true, true, true, true, true, true);
        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, noApiCalls);

        assertTrue(result.getApiCalls().isEmpty());
        assertTrue(result.getEndpointStats().isEmpty());
    }

    @Test
    void jobsDisabledSkipsPairing() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) Job [MyJob] vai ser disparado pelo trigger [cron]",
                "2026-03-30 10:00:01,000 INFO  [stdout] (t1) Job [MyJob] executou em 1000ms and reports: SUCCESS"
        );

        AnalysisOptions noJobs = new AnalysisOptions(true, false, true, true, true, true, true);
        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, noJobs);

        assertTrue(result.getJobExecutions().isEmpty());
    }

    @Test
    void failuresDisabledSkipsDetection() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) ORDEM ORDER 123 FALHA AO INICIAR SYNC: details",
                "2026-03-30 10:00:01,000 INFO  [stdout] (t1) ORDEM ORDER 123 FALHA AO INICIAR SYNC: details again"
        );

        AnalysisOptions noFailures = new AnalysisOptions(true, true, false, true, true, true, true);
        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, noFailures);

        assertTrue(result.getRepeatedFailures().isEmpty());
    }

    // ---- Invalid regex handling ----

    @Test
    void invalidLogLineRegexThrows() {
        LogPreset badPreset = new LogPreset(
                "Bad", "[invalid(regex", "yyyy-MM-dd", null,
                null, null, null, List.of(), List.of(), List.of()
        );

        assertThrows(PatternSyntaxException.class, () ->
                parser.analyze(List.of(tempDir.resolve("dummy.log")), List.of("test.log"), badPreset, 1000, AnalysisOptions.all()));
    }

    // ---- Non-existent file handling ----

    @Test
    void nonExistentFileProducesEmptyResult() {
        Path nonExistent = tempDir.resolve("does-not-exist.log");

        LogAnalysis result = parser.analyze(List.of(nonExistent), List.of("missing.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(0, result.getTotalLineCount());
        assertTrue(result.getAllLines().isEmpty());
    }

    // ---- Empty file ----

    @Test
    void emptyFileProducesEmptyResult() throws IOException {
        Path file = writeLog();

        LogAnalysis result = parser.analyze(List.of(file), List.of("empty.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        // Empty file has a single empty line from the trailing newline
        assertTrue(result.getApiCalls().isEmpty());
        assertTrue(result.getErrors().isEmpty());
    }

    // ---- Non-matching lines become continuation lines ----

    @Test
    void nonMatchingLinesStoredWithNullMetadata() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) Start",
                "This is a plain continuation line",
                "2026-03-30 10:00:01,000 INFO  [stdout] (t1) End"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(3, result.getTotalLineCount());
        // Continuation line has null level/thread/logger
        assertNull(result.getAllLines().get(1).level());
        assertNull(result.getAllLines().get(1).thread());
        assertEquals("This is a plain continuation line", result.getAllLines().get(1).message());
    }

    // ---- Time range calculation ----

    @Test
    void computesTimeRangeFromAllLines() throws IOException {
        Path file = writeLog(
                "2026-03-30 08:00:00,000 INFO  [a] (t1) early",
                "2026-03-30 20:00:00,000 INFO  [a] (t1) late"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertNotNull(result.getTimeRangeStart());
        assertNotNull(result.getTimeRangeEnd());
        assertTrue(result.getTimeRangeStart().isBefore(result.getTimeRangeEnd()));
    }

    // ---- Thread and endpoint extraction ----

    @Test
    void collectsDistinctThreads() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [a] (thread-A) msg1",
                "2026-03-30 10:00:01,000 INFO  [a] (thread-B) msg2",
                "2026-03-30 10:00:02,000 INFO  [a] (thread-A) msg3"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getThreads().size());
        assertTrue(result.getThreads().contains("thread-A"));
        assertTrue(result.getThreads().contains("thread-B"));
    }

    @Test
    void collectsEndpointsFromApiCalls() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) OrderWS/get Request = {}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) OrderWS/get Response = ok",
                "2026-03-30 10:00:01,000 INFO  [stdout] (t1) UserWS/find Request = {}",
                "2026-03-30 10:00:01,100 INFO  [stdout] (t1) UserWS/find Response = ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getEndpoints().size());
        assertTrue(result.getEndpoints().contains("OrderWS/get"));
        assertTrue(result.getEndpoints().contains("UserWS/find"));
    }

    // ---- Error level collection ----

    @Test
    void collectsErrorAndFatalAndSevereLines() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 ERROR [a] (t1) error msg",
                "2026-03-30 10:00:01,000 INFO  [a] (t1) info msg",
                "2026-03-30 10:00:02,000 FATAL [a] (t1) fatal msg",
                "2026-03-30 10:00:03,000 SEVERE [a] (t1) severe msg"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(3, result.getErrors().size());
    }

    // ---- Source files metadata ----

    @Test
    void populatesSourceFileMetadata() throws IOException {
        Path file = writeLog("2026-03-30 10:00:00,000 INFO  [a] (t1) hello");

        LogAnalysis result = parser.analyze(List.of(file), List.of("server.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getSourceFiles().size());
        assertEquals("server.log", result.getSourceFiles().getFirst().filename());
        assertTrue(result.getSourceFiles().getFirst().size() > 0);
    }

    // ---- Multi-file sorting by timestamp ----

    @Test
    void multiFileSortsByTimestamp() throws IOException {
        Path file1 = writeLog(
                "2026-03-30 12:00:00,000 INFO  [a] (t1) middle"
        );
        Path file2 = writeLog(
                "2026-03-30 08:00:00,000 INFO  [a] (t1) earliest"
        );
        Path file3 = writeLog(
                "2026-03-30 18:00:00,000 INFO  [a] (t1) latest"
        );

        LogAnalysis result = parser.analyze(
                List.of(file1, file2, file3),
                List.of("f1.log", "f2.log", "f3.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all()
        );

        assertEquals(3, result.getTotalLineCount());
        assertEquals("earliest", result.getAllLines().get(0).message());
        assertEquals("middle", result.getAllLines().get(1).message());
        assertEquals("latest", result.getAllLines().get(2).message());
    }

    // ---- Slow call detection boundary ----

    @Test
    void callExactlyAtThresholdIsMarkedSlow() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) OrderWS/get Request = {}",
                "2026-03-30 10:00:01,000 INFO  [stdout] (t1) OrderWS/get Response = ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertTrue(result.getApiCalls().getFirst().slow());
    }

    @Test
    void callJustBelowThresholdIsNotSlow() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) OrderWS/get Request = {}",
                "2026-03-30 10:00:00,999 INFO  [stdout] (t1) OrderWS/get Response = ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertFalse(result.getApiCalls().getFirst().slow());
    }

    // ---- Null sensitiveFieldNames in preset ----

    @Test
    void nullSensitiveFieldNamesDoesNotCrash() throws IOException {
        LogPreset presetNullSensitive = new LogPreset(
                "NullSensitive",
                LogPreset.WILDFLY.logLineRegex(),
                LogPreset.WILDFLY.timestampFormat(),
                LogPreset.WILDFLY.apiCallRegex(),
                null, null, null,
                null,
                List.of(),
                List.of()
        );

        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) OrderWS/get Request = {\"token\":\"visible\"}",
                "2026-03-30 10:00:00,100 INFO  [stdout] (t1) OrderWS/get Response = ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), presetNullSensitive, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertTrue(result.getApiCalls().getFirst().requestPayload().contains("visible"));
    }

    // ---- Repeated failure detection: single occurrence not reported ----

    @Test
    void singleFailureOccurrenceNotReported() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) ORDEM ORDER 100 FALHA AO INICIAR SYNC: once only"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getRepeatedFailures().isEmpty());
    }

    @Test
    void multipleFailuresSameEntityReported() throws IOException {
        Path file = writeLog(
                "2026-03-30 10:00:00,000 INFO  [stdout] (t1) ORDEM ORDER 200 FALHA AO INICIAR SYNC: first",
                "2026-03-30 10:00:01,000 INFO  [stdout] (t1) ORDEM ORDER 200 FALHA AO INICIAR SYNC: second"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getRepeatedFailures().size());
        assertEquals(2, result.getRepeatedFailures().getFirst().occurrences());
    }
}
