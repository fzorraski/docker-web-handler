package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.JobExecution;
import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.model.OrphanRequest;
import br.com.fzdevx.domain.model.anomaly.Signal;
import br.com.fzdevx.domain.model.anomaly.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignalExtractorTest {

    private SignalExtractor extractor;
    private final LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);

    @BeforeEach
    void setUp() {
        extractor = new SignalExtractor();
    }

    // ---- API_LATENCY extraction ----

    @Test
    void extract_apiLatency_returnsSignalsFromApiCalls() {
        var apiCalls = List.of(
                new ApiCallPair("/api/users", null, "http-1", now, now.plusSeconds(2), 2000, -1, false,
                        null, null, 1, 5, "server.log", true),
                new ApiCallPair("/api/orders", null, "http-2", now.plusSeconds(5), now.plusSeconds(5), 50, -1, false,
                        null, null, 10, 12, "server.log", false)
        );

        List<Signal> signals = extractor.extract(List.of(), SignalType.API_LATENCY, apiCalls);

        assertEquals(2, signals.size());
        assertEquals(SignalType.API_LATENCY, signals.get(0).signalType());
        assertEquals(2000L, signals.get(0).numericValue());
        assertTrue(signals.get(0).rawMessage().contains("/api/users"));
        assertEquals("http-1", signals.get(0).threadName());
        assertEquals("/api/users", signals.get(0).loggerName());
    }

    @Test
    void extract_apiLatency_skipsCallsWithNullTimestamp() {
        var apiCalls = List.of(
                new ApiCallPair("/api/test", null, "http-1", null, null, 100, -1, false,
                        null, null, 1, 2, "server.log", false)
        );

        List<Signal> signals = extractor.extract(List.of(), SignalType.API_LATENCY, apiCalls);

        assertTrue(signals.isEmpty());
    }

    @Test
    void extract_apiLatency_emptyApiCalls_returnsEmpty() {
        List<Signal> signals = extractor.extract(List.of(), SignalType.API_LATENCY, List.of());
        assertTrue(signals.isEmpty());
    }

    @Test
    void extract_apiLatency_nullApiCalls_returnsEmpty() {
        List<Signal> signals = extractor.extract(List.of(), SignalType.API_LATENCY, null);
        assertTrue(signals.isEmpty());
    }

    // ---- extractAll with API calls ----

    @Test
    void extractAll_includesApiLatencyAlongsideLogSignals() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "http-1", "app", "NullPointerException at Foo.java:10",
                        "server.log")
        );
        var apiCalls = List.of(
                new ApiCallPair("/api/data", null, "http-1", now, now.plusSeconds(1), 1000, -1, false,
                        null, null, 5, 8, "server.log", true)
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(lines, apiCalls);

        assertTrue(result.containsKey(SignalType.API_LATENCY));
        assertTrue(result.containsKey(SignalType.ERROR_COUNT));
        assertTrue(result.containsKey(SignalType.NPE));
        assertEquals(1, result.get(SignalType.API_LATENCY).size());
    }

    @Test
    void extractAll_nullLines_stillReturnsApiLatency() {
        var apiCalls = List.of(
                new ApiCallPair("/api/x", null, "t1", now, now.plusSeconds(1), 500, -1, false,
                        null, null, 1, 2, "server.log", false)
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(null, apiCalls);

        assertTrue(result.containsKey(SignalType.API_LATENCY));
        assertTrue(result.containsKey(SignalType.API_COUNT));
        assertEquals(2, result.size());
    }

    @Test
    void extractAll_noApiCalls_excludesApiLatency() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "Something failed", "server.log")
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(lines, List.of());

        assertFalse(result.containsKey(SignalType.API_LATENCY));
        assertTrue(result.containsKey(SignalType.ERROR_COUNT));
    }

    // ---- detectAvailableTypes with API calls ----

    @Test
    void detectAvailableTypes_includesApiLatencyWhenApiCallsPresent() {
        var apiCalls = List.of(
                new ApiCallPair("/api/test", null, "t1", now, now.plusSeconds(1), 100, -1, false,
                        null, null, 1, 2, "server.log", false)
        );

        List<SignalType> types = extractor.detectAvailableTypes(List.of(), apiCalls);

        assertTrue(types.contains(SignalType.API_LATENCY));
    }

    @Test
    void detectAvailableTypes_excludesApiLatencyWhenNoApiCalls() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "fail", "server.log")
        );

        List<SignalType> types = extractor.detectAvailableTypes(lines, List.of());

        assertFalse(types.contains(SignalType.API_LATENCY));
        assertTrue(types.contains(SignalType.ERROR_COUNT));
    }

    @Test
    void detectAvailableTypes_nullLines_returnsOnlyApiLatency() {
        var apiCalls = List.of(
                new ApiCallPair("/api/x", null, "t1", now, now.plusSeconds(1), 100, -1, false,
                        null, null, 1, 2, "server.log", false)
        );

        List<SignalType> types = extractor.detectAvailableTypes(null, apiCalls);

        assertEquals(List.of(SignalType.API_COUNT, SignalType.API_LATENCY), types);
    }

    @Test
    void detectAvailableTypes_nullLinesAndEmptyApiCalls_returnsEmpty() {
        List<SignalType> types = extractor.detectAvailableTypes(null, List.of());
        assertTrue(types.isEmpty());
    }

    // ---- Backward-compatible overloads ----

    @Test
    void extract_twoArgOverload_delegatesToThreeArg() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "Something bad", "server.log")
        );

        List<Signal> signals = extractor.extract(lines, SignalType.ERROR_COUNT);

        assertEquals(1, signals.size());
    }

    @Test
    void extractAll_oneArgOverload_delegatesToTwoArg() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "NullPointerException", "server.log")
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(lines);

        assertFalse(result.containsKey(SignalType.API_LATENCY));
        assertTrue(result.containsKey(SignalType.ERROR_COUNT));
    }

    @Test
    void detectAvailableTypes_oneArgOverload_delegatesToTwoArg() {
        var lines = List.of(
                new LogLine(1, now, "WARN", "t1", "app", "normal line", "server.log")
        );

        List<SignalType> types = extractor.detectAvailableTypes(lines);

        assertFalse(types.contains(SignalType.API_LATENCY));
    }

    // ---- ORPHAN_REQUEST extraction ----

    @Test
    void extract_orphanRequest_returnsSignals() {
        var orphans = List.of(
                new OrphanRequest("OrderWS/getOrders", "http-1", now, "{\"id\":1}", 42, "server.log"),
                new OrphanRequest("UserWS/getUser", "http-2", now.plusSeconds(5), "{\"id\":2}", 50, "server.log")
        );

        List<Signal> signals = extractor.extract(List.of(), SignalType.ORPHAN_REQUEST, List.of(), orphans);

        assertEquals(2, signals.size());
        assertEquals(SignalType.ORPHAN_REQUEST, signals.get(0).signalType());
        assertEquals("OrderWS/getOrders", signals.get(0).loggerName());
        assertEquals("http-1", signals.get(0).threadName());
        assertEquals(now, signals.get(0).timestamp());
    }

    @Test
    void extract_orphanRequest_skipsNullTimestamp() {
        var orphans = List.of(
                new OrphanRequest("OrderWS/get", "t1", null, "{}", 10, "server.log"),
                new OrphanRequest("UserWS/get", "t1", now, "{}", 20, "server.log")
        );

        List<Signal> signals = extractor.extract(List.of(), SignalType.ORPHAN_REQUEST, List.of(), orphans);

        assertEquals(1, signals.size());
        assertEquals("UserWS/get", signals.getFirst().loggerName());
    }

    @Test
    void extract_orphanRequest_emptyList_returnsEmpty() {
        List<Signal> signals = extractor.extract(List.of(), SignalType.ORPHAN_REQUEST, List.of(), List.of());
        assertTrue(signals.isEmpty());
    }

    @Test
    void extract_orphanRequest_nullList_returnsEmpty() {
        List<Signal> signals = extractor.extract(List.of(), SignalType.ORPHAN_REQUEST, List.of(), null);
        assertTrue(signals.isEmpty());
    }

    @Test
    void extractAll_includesOrphanRequestWithOtherSignals() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "NullPointerException", "server.log")
        );
        var orphans = List.of(
                new OrphanRequest("OrderWS/get", "t1", now, "{}", 10, "server.log")
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(lines, List.of(), orphans);

        assertTrue(result.containsKey(SignalType.ORPHAN_REQUEST));
        assertTrue(result.containsKey(SignalType.ERROR_COUNT));
        assertEquals(1, result.get(SignalType.ORPHAN_REQUEST).size());
    }

    @Test
    void extractAll_noOrphans_excludesOrphanRequest() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "fail", "server.log")
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(lines, List.of(), List.of());

        assertFalse(result.containsKey(SignalType.ORPHAN_REQUEST));
    }

    @Test
    void detectAvailableTypes_includesOrphanRequestWhenOrphansPresent() {
        var orphans = List.of(
                new OrphanRequest("OrderWS/get", "t1", now, "{}", 10, "server.log")
        );

        List<SignalType> types = extractor.detectAvailableTypes(List.of(), List.of(), orphans);

        assertTrue(types.contains(SignalType.ORPHAN_REQUEST));
    }

    @Test
    void detectAvailableTypes_excludesOrphanRequestWhenNoOrphans() {
        List<SignalType> types = extractor.detectAvailableTypes(List.of(), List.of(), List.of());

        assertFalse(types.contains(SignalType.ORPHAN_REQUEST));
    }

    // ---- JOB_DURATION extraction ----

    @Test
    void extract_jobDuration_returnsSignals() {
        var jobs = List.of(
                new JobExecution("CleanupJob", "daily", "sched-1",
                        now, now.plusSeconds(10), 10000, "SUCCESS", 1, 5, "server.log"),
                new JobExecution("BackupJob", "nightly", "sched-2",
                        now.plusSeconds(20), now.plusSeconds(25), 5000, "SUCCESS", 10, 15, "server.log")
        );

        List<Signal> signals = extractor.extract(List.of(), SignalType.JOB_DURATION, List.of(), jobs, List.of());

        assertEquals(2, signals.size());
        assertEquals(SignalType.JOB_DURATION, signals.get(0).signalType());
        assertEquals(10000L, signals.get(0).numericValue());
        assertTrue(signals.get(0).rawMessage().contains("CleanupJob"));
        assertEquals("sched-1", signals.get(0).threadName());
        assertEquals("CleanupJob", signals.get(0).loggerName());
    }

    @Test
    void extract_jobDuration_skipsNullTimestamp() {
        var jobs = List.of(
                new JobExecution("BadJob", "trigger", "t1",
                        null, null, 0, "FAIL", 1, 2, "server.log"),
                new JobExecution("GoodJob", "trigger", "t1",
                        now, now.plusSeconds(1), 1000, "OK", 3, 4, "server.log")
        );

        List<Signal> signals = extractor.extract(List.of(), SignalType.JOB_DURATION, List.of(), jobs, List.of());

        assertEquals(1, signals.size());
        assertEquals("GoodJob", signals.getFirst().loggerName());
    }

    @Test
    void extract_jobDuration_emptyList_returnsEmpty() {
        List<Signal> signals = extractor.extract(List.of(), SignalType.JOB_DURATION, List.of(), List.of(), List.of());
        assertTrue(signals.isEmpty());
    }

    @Test
    void extract_jobDuration_nullList_returnsEmpty() {
        List<Signal> signals = extractor.extract(List.of(), SignalType.JOB_DURATION, List.of(), null, List.of());
        assertTrue(signals.isEmpty());
    }

    @Test
    void extractAll_includesJobDurationWithOtherSignals() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "NullPointerException", "server.log")
        );
        var jobs = List.of(
                new JobExecution("CleanupJob", "daily", "sched-1",
                        now, now.plusSeconds(10), 10000, "SUCCESS", 1, 5, "server.log")
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(lines, List.of(), jobs, List.of());

        assertTrue(result.containsKey(SignalType.JOB_DURATION));
        assertTrue(result.containsKey(SignalType.ERROR_COUNT));
        assertEquals(1, result.get(SignalType.JOB_DURATION).size());
    }

    @Test
    void extractAll_noJobs_excludesJobDuration() {
        var lines = List.of(
                new LogLine(1, now, "ERROR", "t1", "app", "fail", "server.log")
        );

        Map<SignalType, List<Signal>> result = extractor.extractAll(lines, List.of(), List.of(), List.of());

        assertFalse(result.containsKey(SignalType.JOB_DURATION));
    }

    @Test
    void detectAvailableTypes_includesJobDurationWhenJobsPresent() {
        var jobs = List.of(
                new JobExecution("CleanupJob", "daily", "sched-1",
                        now, now.plusSeconds(10), 10000, "SUCCESS", 1, 5, "server.log")
        );

        List<SignalType> types = extractor.detectAvailableTypes(List.of(), List.of(), jobs, List.of());

        assertTrue(types.contains(SignalType.JOB_DURATION));
    }

    @Test
    void detectAvailableTypes_excludesJobDurationWhenNoJobs() {
        List<SignalType> types = extractor.detectAvailableTypes(List.of(), List.of(), List.of(), List.of());

        assertFalse(types.contains(SignalType.JOB_DURATION));
    }
}
