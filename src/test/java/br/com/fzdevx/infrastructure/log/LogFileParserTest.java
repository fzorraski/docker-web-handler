package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogFileParserTest {

    private LogFileParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new LogFileParser();
        setField("maxStoredLines", 500_000);
        setField("analysisExecutor", java.util.concurrent.Executors.newSingleThreadExecutor());
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

    // ---- Log line parsing ----

    @Test
    void parsesWildFlyLogLines() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:13,938 INFO  [stdout] (default task-11155) Some message",
                "2026-03-30 07:31:14,021 ERROR [org.hibernate] (default task-11155) Something failed"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getTotalLineCount());
        assertEquals("INFO", result.getAllLines().get(0).level());
        assertEquals("stdout", result.getAllLines().get(0).logger());
        assertEquals("default task-11155", result.getAllLines().get(0).thread());
        assertEquals("Some message", result.getAllLines().get(0).message());
        assertEquals("test.log", result.getAllLines().get(0).sourceFile());
    }

    @Test
    void countsByLevel() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:01,000 INFO  [a] (t1) msg1",
                "2026-03-30 00:00:02,000 INFO  [a] (t1) msg2",
                "2026-03-30 00:00:03,000 WARNING [a] (t1) msg3",
                "2026-03-30 00:00:04,000 ERROR [a] (t1) msg4"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getLevelCounts().get("INFO"));
        assertEquals(1, result.getLevelCounts().get("WARNING"));
        assertEquals(1, result.getLevelCounts().get("ERROR"));
    }

    @Test
    void extractsDistinctThreads() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:01,000 INFO  [a] (thread-1) msg1",
                "2026-03-30 00:00:02,000 INFO  [a] (thread-2) msg2",
                "2026-03-30 00:00:03,000 INFO  [a] (thread-1) msg3"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getThreads().size());
        assertTrue(result.getThreads().contains("thread-1"));
        assertTrue(result.getThreads().contains("thread-2"));
    }

    @Test
    void collectsErrorLines() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:01,000 INFO  [a] (t1) ok",
                "2026-03-30 00:00:02,000 ERROR [a] (t1) bad",
                "2026-03-30 00:00:03,000 FATAL [a] (t1) very bad"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getErrors().size());
        assertEquals("bad", result.getErrors().get(0).message());
        assertEquals("very bad", result.getErrors().get(1).message());
    }

    // ---- API call pairing WITH correlationId ----

    @Test
    void pairsApiCallsWithCorrelationId() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:13,938 INFO  [stdout] (default task-1) CustomerOrderResource/update 73938 Request = Body: {\"id\":1}",
                "2026-03-30 07:31:14,021 INFO  [stdout] (default task-1) CustomerOrderResource/update 73938 Response = {\"id\":1,\"status\":\"ok\"}"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals("CustomerOrderResource/update", pair.endpoint());
        assertEquals("73938", pair.correlationId());
        assertEquals("default task-1", pair.thread());
        assertEquals(83, pair.durationMs());
        assertTrue(pair.requestPayload().contains("Body:"));
        assertTrue(pair.responsePayload().contains("status"));
        assertFalse(pair.slow());
    }

    // ---- API call pairing WITHOUT correlationId (FIFO) ----

    @Test
    void pairsApiCallsWithoutCorrelationId() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:24,637 INFO  [stdout] (default task-1) AplicativoWS/getApk Request = 6185000",
                "2026-03-30 07:31:24,643 INFO  [stdout] (default task-1) AplicativoWS/getApk Response = ALREADY_WITH_LAST_VERSION"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals("AplicativoWS/getApk", pair.endpoint());
        assertNull(pair.correlationId());
        assertEquals(6, pair.durationMs());
        assertEquals("6185000", pair.requestPayload());
        assertEquals("ALREADY_WITH_LAST_VERSION", pair.responsePayload());
    }

    @Test
    void fifoQueuePairsMultipleCallsOnSameThread() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (task-1) OrderWS/getOrders Request = {\"first\":true}",
                "2026-03-30 07:31:00,100 INFO  [stdout] (task-1) OrderWS/getOrders Response = [1]",
                "2026-03-30 07:31:01,000 INFO  [stdout] (task-1) OrderWS/getOrders Request = {\"second\":true}",
                "2026-03-30 07:31:01,200 INFO  [stdout] (task-1) OrderWS/getOrders Response = [2]"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getApiCalls().size());
        assertEquals("{\"first\":true}", result.getApiCalls().get(0).requestPayload());
        assertEquals("[1]", result.getApiCalls().get(0).responsePayload());
        assertEquals(100, result.getApiCalls().get(0).durationMs());

        assertEquals("{\"second\":true}", result.getApiCalls().get(1).requestPayload());
        assertEquals("[2]", result.getApiCalls().get(1).responsePayload());
        assertEquals(200, result.getApiCalls().get(1).durationMs());
    }

    @Test
    void separateThreadsPairIndependently() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (task-1) OrderWS/getOrders Request = req1",
                "2026-03-30 07:31:00,050 INFO  [stdout] (task-2) OrderWS/getOrders Request = req2",
                "2026-03-30 07:31:00,100 INFO  [stdout] (task-2) OrderWS/getOrders Response = resp2",
                "2026-03-30 07:31:00,200 INFO  [stdout] (task-1) OrderWS/getOrders Response = resp1"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getApiCalls().size());

        ApiCallPair task1Pair = result.getApiCalls().stream()
                .filter(p -> "task-1".equals(p.thread())).findFirst().orElseThrow();
        assertEquals("req1", task1Pair.requestPayload());
        assertEquals("resp1", task1Pair.responsePayload());
        assertEquals(200, task1Pair.durationMs());

        ApiCallPair task2Pair = result.getApiCalls().stream()
                .filter(p -> "task-2".equals(p.thread())).findFirst().orElseThrow();
        assertEquals("req2", task2Pair.requestPayload());
        assertEquals("resp2", task2Pair.responsePayload());
        assertEquals(50, task2Pair.durationMs());
    }

    // ---- Slow call detection ----

    @Test
    void marksSlowCalls() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (task-1) AplicativoWS/doLogin Request = req",
                "2026-03-30 07:31:02,500 INFO  [stdout] (task-1) AplicativoWS/doLogin Response = resp"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertTrue(result.getApiCalls().getFirst().slow());
        assertEquals(2500, result.getApiCalls().getFirst().durationMs());
    }

    // ---- Endpoint stats ----

    @Test
    void computesEndpointStats() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r1",
                "2026-03-30 07:31:00,100 INFO  [stdout] (t1) OrderWS/getOrders Response = []",
                "2026-03-30 07:31:01,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r2",
                "2026-03-30 07:31:01,300 INFO  [stdout] (t1) OrderWS/getOrders Response = []",
                "2026-03-30 07:31:02,000 INFO  [stdout] (t1) AplicativoWS/getApk Request = v",
                "2026-03-30 07:31:02,010 INFO  [stdout] (t1) AplicativoWS/getApk Response = ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getEndpointStats().size());
        EndpointStats ordersStats = result.getEndpointStats().stream()
                .filter(s -> s.endpoint().equals("OrderWS/getOrders")).findFirst().orElseThrow();
        assertEquals(2, ordersStats.callCount());
        assertEquals(100, ordersStats.minDurationMs());
        assertEquals(300, ordersStats.maxDurationMs());
        assertEquals(200.0, ordersStats.avgDurationMs(), 0.1);
    }

    // ---- Job execution pairing ----

    @Test
    void pairsQuartzJobStartAndEnd() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,064 INFO  [org.quartz] (Worker-7) Job [Inicio de Ordem.Jobs_WMS] vai ser disparado pelo trigger [TriggerInicio93.DEFAULT]",
                "2026-03-30 00:00:10,285 INFO  [org.quartz] (Worker-7) Job [Inicio de Ordem.Jobs_WMS] executou em  30/03/2026 00:00:10 and reports: null"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getJobExecutions().size());
        JobExecution job = result.getJobExecutions().getFirst();
        assertEquals("Inicio de Ordem.Jobs_WMS", job.jobName());
        assertEquals("TriggerInicio93.DEFAULT", job.triggerName());
        assertEquals("Worker-7", job.thread());
        assertEquals(10221, job.durationMs());
        assertEquals("null", job.result());
    }

    @Test
    void noJobsWhenPresetHasNoJobPattern() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,064 INFO  [org.quartz] (Worker-7) Job [Test.Jobs] vai ser disparado pelo trigger [T.DEFAULT]",
                "2026-03-30 00:00:10,285 INFO  [org.quartz] (Worker-7) Job [Test.Jobs] executou em  30/03/2026 00:00:10 and reports: null"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.QUARKUS, 1000, AnalysisOptions.all());

        assertTrue(result.getJobExecutions().isEmpty());
    }

    // ---- Repeated failure detection ----

    @Test
    void detectsRepeatedFailures() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:03,586 INFO  [jobs] (W-7) ORDEM ORDER 252730 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0, 34603]",
                "2026-03-30 00:00:40,822 INFO  [jobs] (W-22) ORDEM ORDER 252730 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0, 34603]",
                "2026-03-30 00:01:20,100 INFO  [jobs] (W-7) ORDEM ORDER 252730 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0, 34603]",
                "2026-03-30 00:00:04,796 INFO  [jobs] (W-7) ORDEM ORDER 252603 FALHA AO INICIAR NO_PRODUCTS_HAVE_STOCK: [ORDER 252603]"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getRepeatedFailures().size());
        RepeatedFailure failure = result.getRepeatedFailures().getFirst();
        assertEquals("ORDER 252730", failure.entityId());
        assertEquals("UNSUFFICIENT_AMOUNT", failure.reason());
        assertEquals(3, failure.occurrences());
        assertEquals(3, failure.details().size());
    }

    @Test
    void noFailuresWhenPresetHasNoPattern() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:03,586 INFO  [jobs] (W-7) ORDEM ORDER 252730 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0]"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.QUARKUS, 1000, AnalysisOptions.all());

        assertTrue(result.getRepeatedFailures().isEmpty());
    }

    // ---- Multi-file ----

    @Test
    void mergesMultipleFilesByTimestamp() throws IOException {
        Path file1 = writeLog(tempDir.resolve("server1.log"),
                "2026-03-30 07:31:00,000 INFO  [a] (t1) msg-first",
                "2026-03-30 07:31:02,000 INFO  [a] (t1) msg-third"
        );
        Path file2 = writeLog(tempDir.resolve("server2.log"),
                "2026-03-30 07:31:01,000 INFO  [a] (t1) msg-second",
                "2026-03-30 07:31:03,000 INFO  [a] (t1) msg-fourth"
        );

        LogAnalysis result = parser.analyze(
                List.of(file1, file2), List.of("server1.log", "server2.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all()
        );

        assertEquals(4, result.getTotalLineCount());
        assertEquals(2, result.getSourceFiles().size());
        assertEquals("msg-first", result.getAllLines().get(0).message());
        assertEquals("server1.log", result.getAllLines().get(0).sourceFile());
        assertEquals("msg-second", result.getAllLines().get(1).message());
        assertEquals("server2.log", result.getAllLines().get(1).sourceFile());
        assertEquals("msg-third", result.getAllLines().get(2).message());
        assertEquals("msg-fourth", result.getAllLines().get(3).message());
    }

    // ---- Time range ----

    @Test
    void calculatesTimeRange() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:01,000 INFO  [a] (t1) first",
                "2026-03-30 23:59:59,999 INFO  [a] (t1) last"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertNotNull(result.getTimeRangeStart());
        assertNotNull(result.getTimeRangeEnd());
        assertEquals(2026, result.getTimeRangeStart().getYear());
        assertEquals(23, result.getTimeRangeEnd().getHour());
    }

    // ---- Non-matching lines ----

    @Test
    void handlesNonMatchingLines() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:01,000 INFO  [a] (t1) structured line",
                "	at org.example.SomeClass.method(SomeClass.java:42)",
                "random garbage"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(3, result.getTotalLineCount());
        assertNotNull(result.getAllLines().get(0).level());
        assertNull(result.getAllLines().get(1).level());
        assertNull(result.getAllLines().get(2).level());
    }

    // ---- Edge: empty file ----

    @Test
    void emptyFile_producesEmptyAnalysis() throws IOException {
        Path file = tempDir.resolve("empty-" + System.nanoTime() + ".log");
        Files.writeString(file, "");

        LogAnalysis result = parser.analyze(List.of(file), List.of("empty.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(0, result.getTotalLineCount());
        assertTrue(result.getApiCalls().isEmpty());
        assertTrue(result.getThreads().isEmpty());
        assertTrue(result.getEndpoints().isEmpty());
        assertNull(result.getTimeRangeStart());
        assertNull(result.getTimeRangeEnd());
    }

    // ---- Edge: orphan response without request ----

    @Test
    void orphanResponse_notPaired() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (task-1) OrderWS/getOrders Response = []"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().isEmpty());
    }

    // ---- Edge: orphan request without response ----

    @Test
    void orphanRequest_notPaired() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (task-1) OrderWS/getOrders Request = {}"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().isEmpty());
    }

    // ---- Edge: mismatched correlationId not paired ----

    @Test
    void mismatchedCorrelationId_notPaired() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (task-1) CustomerOrderResource/update 111 Request = req",
                "2026-03-30 07:31:00,100 INFO  [stdout] (task-1) CustomerOrderResource/update 222 Response = resp"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().isEmpty());
    }

    // ---- Edge: slow threshold exactly at boundary ----

    @Test
    void exactlyAtSlowThreshold_isSlow() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) AplicativoWS/doLogin Request = r",
                "2026-03-30 07:31:01,000 INFO  [stdout] (t1) AplicativoWS/doLogin Response = r"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().getFirst().slow());
        assertEquals(1000, result.getApiCalls().getFirst().durationMs());
    }

    @Test
    void belowSlowThreshold_isNotSlow() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) AplicativoWS/doLogin Request = r",
                "2026-03-30 07:31:00,999 INFO  [stdout] (t1) AplicativoWS/doLogin Response = r"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertFalse(result.getApiCalls().getFirst().slow());
    }

    // ---- Edge: multiple different endpoints on same thread ----

    @Test
    void differentEndpointsSameThread_pairedCorrectly() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r1",
                "2026-03-30 07:31:00,050 INFO  [stdout] (t1) AplicativoWS/getApk Request = r2",
                "2026-03-30 07:31:00,100 INFO  [stdout] (t1) AplicativoWS/getApk Response = resp2",
                "2026-03-30 07:31:00,200 INFO  [stdout] (t1) OrderWS/getOrders Response = resp1"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getApiCalls().size());
        ApiCallPair orderPair = result.getApiCalls().stream()
                .filter(p -> p.endpoint().equals("OrderWS/getOrders")).findFirst().orElseThrow();
        assertEquals("r1", orderPair.requestPayload());
        assertEquals("resp1", orderPair.responsePayload());
        assertEquals(200, orderPair.durationMs());

        ApiCallPair apkPair = result.getApiCalls().stream()
                .filter(p -> p.endpoint().equals("AplicativoWS/getApk")).findFirst().orElseThrow();
        assertEquals("r2", apkPair.requestPayload());
        assertEquals("resp2", apkPair.responsePayload());
        assertEquals(50, apkPair.durationMs());
    }

    // ---- Edge: stale request in FIFO queue, closest match preferred ----

    @Test
    void staleRequestInQueue_closestMatchPreferred() throws IOException {
        // Simulates: old request at 07:00, new request at 07:10, response at 07:10.
        // The response at 07:10 should pair with 07:10 request (closest), not 07:00 (FIFO oldest).
        Path file = writeLog(
                "2026-03-30 07:00:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = old-request",
                "2026-03-30 07:10:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = new-request",
                "2026-03-30 07:10:00,500 INFO  [stdout] (t1) OrderWS/getOrders Response = response-for-new"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        // Should pair new-request with response (500ms), not old-request with response (10 min)
        assertEquals(1, result.getApiCalls().size());
        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals("new-request", pair.requestPayload());
        assertEquals("response-for-new", pair.responsePayload());
        assertEquals(500, pair.durationMs());
    }

    @Test
    void multipleStaleRequests_closestMatchWins() throws IOException {
        // 3 requests queued, response arrives close to the last one
        Path file = writeLog(
                "2026-03-30 07:00:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = req1",
                "2026-03-30 07:05:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = req2",
                "2026-03-30 07:10:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = req3",
                "2026-03-30 07:10:00,200 INFO  [stdout] (t1) OrderWS/getOrders Response = resp3"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertEquals("req3", result.getApiCalls().getFirst().requestPayload());
        assertEquals(200, result.getApiCalls().getFirst().durationMs());
    }

    // ---- Orphan request collection ----

    @Test
    void orphanRequest_unparedRequestCollected() throws IOException {
        // One request with no response → should become an orphan
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = orphan-payload"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().isEmpty());
        assertEquals(1, result.getOrphanRequests().size());
        assertEquals("OrderWS/getOrders", result.getOrphanRequests().getFirst().endpoint());
        assertEquals("t1", result.getOrphanRequests().getFirst().thread());
        assertEquals("orphan-payload", result.getOrphanRequests().getFirst().payload());
        assertEquals(1, result.getOrphanRequests().getFirst().lineNumber());
    }

    @Test
    void orphanRequest_pairedCallHasNoOrphan() throws IOException {
        // One request + one response → no orphan
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = req",
                "2026-03-30 07:31:00,100 INFO  [stdout] (t1) OrderWS/getOrders Response = resp"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertTrue(result.getOrphanRequests().isEmpty());
    }

    @Test
    void orphanRequest_staleRequestBecomesOrphan() throws IOException {
        // Old request at 07:00, new request at 07:10 + response at 07:10.
        // The 07:10 request pairs with response; the 07:00 request becomes orphan.
        Path file = writeLog(
                "2026-03-30 07:00:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = stale",
                "2026-03-30 07:10:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = fresh",
                "2026-03-30 07:10:00,500 INFO  [stdout] (t1) OrderWS/getOrders Response = resp"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertEquals("fresh", result.getApiCalls().getFirst().requestPayload());
        assertEquals(1, result.getOrphanRequests().size());
        assertEquals("stale", result.getOrphanRequests().getFirst().payload());
    }

    @Test
    void orphanRequest_multipleOrphansSortedByLineNumber() throws IOException {
        // Two orphan requests on different threads
        Path file = writeLog(
                "2026-03-30 07:00:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = orphan1",
                "2026-03-30 07:01:00,000 INFO  [stdout] (t2) UserWS/getUser Request = orphan2"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().isEmpty());
        assertEquals(2, result.getOrphanRequests().size());
        assertTrue(result.getOrphanRequests().get(0).lineNumber() < result.getOrphanRequests().get(1).lineNumber());
    }

    @Test
    void orphanRequest_withCorrelationId_collected() throws IOException {
        // Request with correlationId but no response
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders 12345 Request = orphan-with-id"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().isEmpty());
        assertEquals(1, result.getOrphanRequests().size());
        assertEquals("orphan-with-id", result.getOrphanRequests().getFirst().payload());
    }

    // ---- Edge: p95 with single call ----

    @Test
    void singleCall_p95EqualsActualDuration() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r",
                "2026-03-30 07:31:00,150 INFO  [stdout] (t1) OrderWS/getOrders Response = r"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        EndpointStats stats = result.getEndpointStats().getFirst();
        assertEquals(150, stats.p95DurationMs());
        assertEquals(150, stats.minDurationMs());
        assertEquals(150, stats.maxDurationMs());
    }

    // ---- Edge: endpoint stats slow count ----

    @Test
    void endpointStats_countsSlowCalls() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r1",
                "2026-03-30 07:31:00,050 INFO  [stdout] (t1) OrderWS/getOrders Response = []",
                "2026-03-30 07:31:01,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r2",
                "2026-03-30 07:31:03,000 INFO  [stdout] (t1) OrderWS/getOrders Response = []"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        EndpointStats stats = result.getEndpointStats().getFirst();
        assertEquals(2, stats.callCount());
        assertEquals(1, stats.slowCount());
    }

    // ---- Edge: multiple job executions same name ----

    @Test
    void multipleJobExecutionsSameName() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 00:00:05,000 INFO  [q] (W-1) Job [MyJob.Jobs] executou em  30/03/2026 00:00:05 and reports: null",
                "2026-03-30 00:00:40,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 00:00:48,000 INFO  [q] (W-1) Job [MyJob.Jobs] executou em  30/03/2026 00:00:48 and reports: ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getJobExecutions().size());
        assertEquals(5000, result.getJobExecutions().get(0).durationMs());
        assertEquals("null", result.getJobExecutions().get(0).result());
        assertEquals(8000, result.getJobExecutions().get(1).durationMs());
        assertEquals("ok", result.getJobExecutions().get(1).result());
    }

    // ---- Job failure matching ----

    @Test
    void jobFailureLineMatchedAsEnd() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 00:00:02,000 WARN  [q] (W-1) Job [MyJob.Jobs] execucao falhou com o erro: org.quartz.SchedulerException: Job threw an unhandled exception."
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getJobExecutions().size());
        JobExecution job = result.getJobExecutions().getFirst();
        assertEquals("MyJob.Jobs", job.jobName());
        assertEquals(2000, job.durationMs());
        assertTrue(job.result().contains("SchedulerException"));
        assertTrue(result.getOrphanJobs().isEmpty());
    }

    @Test
    void jobFailureDoesNotCascadeMispair() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 00:00:02,000 WARN  [q] (W-1) Job [MyJob.Jobs] execucao falhou com o erro: RollbackException",
                "2026-03-30 00:10:00,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 00:10:03,000 INFO  [q] (W-1) Job [MyJob.Jobs] executou em  30/03/2026 00:10:03 and reports: null"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getJobExecutions().size());
        assertEquals(2000, result.getJobExecutions().get(0).durationMs());
        assertEquals(3000, result.getJobExecutions().get(1).durationMs());
    }

    // ---- Job closest-timestamp matching ----

    @Test
    void jobPairingUsesClosestTimestamp() throws IOException {
        // Simulate: start A, start B, end B (closest to B), end A (closest to A)
        // Without closest-timestamp, FIFO would mispair A->endB and B->endA
        Path file = writeLog(
                "2026-03-30 01:00:00,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 01:05:00,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 01:05:02,000 INFO  [q] (W-1) Job [MyJob.Jobs] executou em  30/03/2026 01:05:02 and reports: ok",
                "2026-03-30 01:00:03,000 INFO  [q] (W-1) Job [MyJob.Jobs] executou em  30/03/2026 01:00:03 and reports: null"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getJobExecutions().size());
        // Closest-timestamp should pair start@01:05 with end@01:05:02 (2s) and start@01:00 with end@01:00:03 (3s)
        assertEquals(2000, result.getJobExecutions().get(0).durationMs());
        assertEquals(3000, result.getJobExecutions().get(1).durationMs());
    }

    // ---- Orphan job tracking ----

    @Test
    void orphanJobsCollectedForUnmatchedStarts() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,000 INFO  [q] (W-1) Job [MyJob.Jobs] vai ser disparado pelo trigger [T1.DEFAULT]",
                "2026-03-30 00:00:05,000 INFO  [q] (W-1) Job [MyJob.Jobs] executou em  30/03/2026 00:00:05 and reports: null",
                "2026-03-30 00:01:00,000 INFO  [q] (W-2) Job [OrphanJob.Jobs] vai ser disparado pelo trigger [T2.DEFAULT]"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(1, result.getJobExecutions().size());
        assertEquals(1, result.getOrphanJobs().size());
        assertEquals("OrphanJob.Jobs", result.getOrphanJobs().getFirst().jobName());
        assertEquals("T2.DEFAULT", result.getOrphanJobs().getFirst().triggerName());
        assertEquals("W-2", result.getOrphanJobs().getFirst().thread());
    }

    // ---- Edge: single failure not reported as repeated ----

    @Test
    void singleFailure_notReported() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,000 INFO  [j] (W-1) ORDEM ORDER 999 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0, 123]"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getRepeatedFailures().isEmpty());
    }

    // ---- Edge: different failure reasons for same entity counted separately ----

    @Test
    void differentReasonsForSameEntity_separateGroups() throws IOException {
        Path file = writeLog(
                "2026-03-30 00:00:00,000 INFO  [j] (W-1) ORDEM ORDER 100 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0]",
                "2026-03-30 00:00:01,000 INFO  [j] (W-1) ORDEM ORDER 100 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0]",
                "2026-03-30 00:00:02,000 INFO  [j] (W-1) ORDEM ORDER 100 FALHA AO INICIAR NO_PRODUCTS_HAVE_STOCK: [ORDER 100]",
                "2026-03-30 00:00:03,000 INFO  [j] (W-1) ORDEM ORDER 100 FALHA AO INICIAR NO_PRODUCTS_HAVE_STOCK: [ORDER 100]"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getRepeatedFailures().size());
        assertTrue(result.getRepeatedFailures().stream().allMatch(f -> f.occurrences() == 2));
    }

    // ---- Spring Boot preset ----

    @Test
    void springBootPreset_parsesLogLines() throws IOException {
        Path file = writeLog(
                "2026-03-30T07:31:13.938-03:00 INFO  12345 --- [main] com.example.App : Application started"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.SPRING_BOOT, 1000, AnalysisOptions.all());

        assertEquals(1, result.getTotalLineCount());
        LogLine line = result.getAllLines().getFirst();
        assertEquals("INFO", line.level());
        assertEquals("main", line.thread());
        assertEquals("com.example.App", line.logger());
        assertEquals("Application started", line.message());
    }

    // ---- Multi-file: API calls paired across merged timeline ----

    @Test
    void multiFile_apiCallsPairedAcrossFiles() throws IOException {
        Path file1 = writeLog(tempDir.resolve("a.log"),
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = req-from-file1"
        );
        Path file2 = writeLog(tempDir.resolve("b.log"),
                "2026-03-30 07:31:00,500 INFO  [stdout] (t1) Some other log line",
                "2026-03-30 07:31:01,000 INFO  [stdout] (t1) OrderWS/getOrders Response = resp-from-file2"
        );

        LogAnalysis result = parser.analyze(
                List.of(file1, file2), List.of("a.log", "b.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all()
        );

        assertEquals(1, result.getApiCalls().size());
        assertEquals("req-from-file1", result.getApiCalls().getFirst().requestPayload());
        assertEquals("resp-from-file2", result.getApiCalls().getFirst().responsePayload());
        assertEquals(1000, result.getApiCalls().getFirst().durationMs());
    }

    // ---- Endpoints list populated from API calls ----

    @Test
    void endpointsListPopulatedFromApiCalls() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:00,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r",
                "2026-03-30 07:31:00,100 INFO  [stdout] (t1) OrderWS/getOrders Response = r",
                "2026-03-30 07:31:01,000 INFO  [stdout] (t1) AplicativoWS/getApk Request = r",
                "2026-03-30 07:31:01,010 INFO  [stdout] (t1) AplicativoWS/getApk Response = r"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getEndpoints().size());
        assertTrue(result.getEndpoints().contains("OrderWS/getOrders"));
        assertTrue(result.getEndpoints().contains("AplicativoWS/getApk"));
    }

    // ---- Max stored lines ----

    @Test
    void maxStoredLines_trimsToLastNLines() throws IOException {
        setField("maxStoredLines", 3);
        var lines = new String[5];
        for (int i = 0; i < 5; i++) {
            lines[i] = String.format("2026-03-30 00:00:%02d,000 INFO  [a] (t1) line-%d", i, i + 1);
        }
        Path file = writeLog(lines);

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(5, result.getTotalLineCount());
        assertEquals(3, result.getAllLines().size());
        assertEquals("line-3", result.getAllLines().get(0).message());
        assertEquals("line-4", result.getAllLines().get(1).message());
        assertEquals("line-5", result.getAllLines().get(2).message());
    }

    @Test
    void maxStoredLines_zeroKeepsAll() throws IOException {
        setField("maxStoredLines", 0);
        var lines = new String[10];
        for (int i = 0; i < 10; i++) {
            lines[i] = String.format("2026-03-30 00:00:%02d,000 INFO  [a] (t1) line-%d", i, i + 1);
        }
        Path file = writeLog(lines);

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(10, result.getTotalLineCount());
        assertEquals(10, result.getAllLines().size());
    }

    @Test
    void maxStoredLines_belowLimit_noTrimming() throws IOException {
        setField("maxStoredLines", 100);
        Path file = writeLog(
                "2026-03-30 00:00:01,000 INFO  [a] (t1) first",
                "2026-03-30 00:00:02,000 INFO  [a] (t1) second"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertEquals(2, result.getTotalLineCount());
        assertEquals(2, result.getAllLines().size());
    }

    @Test
    void maxStoredLines_analysisRunsOnAllLinesBeforeTrimming() throws IOException {
        setField("maxStoredLines", 2);
        Path file = writeLog(
                "2026-03-30 00:00:01,000 INFO  [stdout] (t1) OrderWS/getOrders Request = r1",
                "2026-03-30 00:00:01,100 INFO  [stdout] (t1) OrderWS/getOrders Response = []",
                "2026-03-30 00:00:02,000 INFO  [stdout] (t1) AplicativoWS/getApk Request = v",
                "2026-03-30 00:00:02,010 INFO  [stdout] (t1) AplicativoWS/getApk Response = ok"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        // All 4 lines were analyzed for API calls
        assertEquals(2, result.getApiCalls().size());
        assertEquals(2, result.getEndpointStats().size());
        // But only last 2 lines stored for browsing
        assertEquals(4, result.getTotalLineCount());
        assertEquals(2, result.getAllLines().size());
    }

    // ---- Nginx single-line mode ----

    @Test
    void parsesNginxCombinedFormat() throws IOException {
        Path file = writeLog(
                "192.168.1.1 - frank [29/Apr/2026:10:30:00 -0300] \"GET /api/users HTTP/1.1\" 200 1234 \"http://example.com\" \"Mozilla/5.0\""
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(1, result.getTotalLineCount());
        LogLine line = result.getAllLines().getFirst();
        assertEquals("192.168.1.1", line.thread());
        assertEquals("200", line.level());
        assertEquals("GET /api/users", line.logger());
        assertNotNull(line.timestamp());
        assertTrue(line.message().contains("GET /api/users"));
        assertTrue(line.message().contains("200"));
    }

    @Test
    void parsesNginxCacheLogFormat() throws IOException {
        Path file = writeLog(
                "10.0.0.5 - [29/Apr/2026:14:22:33 -0300] \"POST /api/orders HTTP/1.1\" 201 567 cache=MISS rt=0.045 urt=0.032 resp_size=567"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("cache.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(1, result.getTotalLineCount());
        LogLine line = result.getAllLines().getFirst();
        assertEquals("10.0.0.5", line.thread());
        assertEquals("201", line.level());
        assertEquals("POST /api/orders", line.logger());
    }

    @Test
    void nginxSingleLineCreatesApiCallPairs() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/users HTTP/1.1\" 200 500 cache=HIT rt=0.025 urt=0.010 resp_size=500",
                "10.0.0.2 - [29/Apr/2026:10:00:01 -0300] \"POST /api/orders HTTP/1.1\" 201 100 cache=MISS rt=1.500 urt=1.200 resp_size=100",
                "10.0.0.1 - [29/Apr/2026:10:00:02 -0300] \"GET /api/users HTTP/1.1\" 200 500 cache=HIT rt=0.015 urt=0.005 resp_size=500"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(3, result.getApiCalls().size());

        ApiCallPair first = result.getApiCalls().get(0);
        assertEquals("GET /api/users", first.endpoint());
        assertEquals(25, first.durationMs());
        assertFalse(first.slow());
        assertEquals("10.0.0.1", first.thread());

        ApiCallPair second = result.getApiCalls().get(1);
        assertEquals("POST /api/orders", second.endpoint());
        assertEquals(1500, second.durationMs());
        assertTrue(second.slow());
    }

    @Test
    void nginxWithoutDurationDefaultsToZero() throws IOException {
        Path file = writeLog(
                "192.168.1.1 - user [29/Apr/2026:10:00:00 -0300] \"GET /index.html HTTP/1.1\" 200 5000 \"http://ref\" \"Mozilla/5.0\""
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(1, result.getApiCalls().size());
        assertEquals(0, result.getApiCalls().getFirst().durationMs());
        assertFalse(result.getApiCalls().getFirst().slow());
    }

    @Test
    void nginxQueryParamsStrippedFromEndpoint() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/users?page=1&sort=name HTTP/1.1\" 200 500 cache=HIT rt=0.010 urt=0.005 resp_size=500",
                "10.0.0.2 - [29/Apr/2026:10:00:01 -0300] \"GET /api/users?page=2 HTTP/1.1\" 200 500 cache=HIT rt=0.012 urt=0.006 resp_size=500"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(2, result.getApiCalls().size());
        assertEquals("GET /api/users", result.getApiCalls().get(0).endpoint());
        assertEquals("GET /api/users", result.getApiCalls().get(1).endpoint());

        assertEquals(1, result.getEndpointStats().size());
        assertEquals("GET /api/users", result.getEndpointStats().getFirst().endpoint());
        assertEquals(2, result.getEndpointStats().getFirst().callCount());
    }

    @Test
    void nginxEndpointStatsComputed() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/data HTTP/1.1\" 200 100 cache=HIT rt=0.100 urt=0.050 resp_size=100",
                "10.0.0.2 - [29/Apr/2026:10:00:01 -0300] \"GET /api/data HTTP/1.1\" 200 100 cache=MISS rt=0.300 urt=0.250 resp_size=100",
                "10.0.0.3 - [29/Apr/2026:10:00:02 -0300] \"GET /api/data HTTP/1.1\" 200 100 cache=MISS rt=2.000 urt=1.900 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(1, result.getEndpointStats().size());
        EndpointStats stats = result.getEndpointStats().getFirst();
        assertEquals("GET /api/data", stats.endpoint());
        assertEquals(3, stats.callCount());
        assertEquals(100, stats.minDurationMs());
        assertEquals(2000, stats.maxDurationMs());
        assertEquals(1, stats.slowCount());
    }

    @Test
    void nginxStatusCodeDistribution() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /ok HTTP/1.1\" 200 100 cache=HIT rt=0.010 urt=0.005 resp_size=100",
                "10.0.0.2 - [29/Apr/2026:10:00:01 -0300] \"GET /ok HTTP/1.1\" 200 100 cache=HIT rt=0.010 urt=0.005 resp_size=100",
                "10.0.0.3 - [29/Apr/2026:10:00:02 -0300] \"GET /missing HTTP/1.1\" 404 0 cache=- rt=0.001 urt=- resp_size=0",
                "10.0.0.4 - [29/Apr/2026:10:00:03 -0300] \"GET /error HTTP/1.1\" 500 0 cache=- rt=0.001 urt=- resp_size=0"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(2, result.getLevelCounts().get("200"));
        assertEquals(1, result.getLevelCounts().get("404"));
        assertEquals(1, result.getLevelCounts().get("500"));
    }

    @Test
    void nginxTopIpsViaThreads() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /a HTTP/1.1\" 200 100 cache=HIT rt=0.010 urt=0.005 resp_size=100",
                "10.0.0.1 - [29/Apr/2026:10:00:01 -0300] \"GET /b HTTP/1.1\" 200 100 cache=HIT rt=0.010 urt=0.005 resp_size=100",
                "10.0.0.2 - [29/Apr/2026:10:00:02 -0300] \"GET /a HTTP/1.1\" 200 100 cache=HIT rt=0.010 urt=0.005 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertEquals(2, result.getThreads().size());
        assertTrue(result.getThreads().contains("10.0.0.1"));
        assertTrue(result.getThreads().contains("10.0.0.2"));
    }

    @Test
    void nginxNoOrphansInSingleLineMode() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api HTTP/1.1\" 200 100 cache=HIT rt=0.010 urt=0.005 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertTrue(result.getOrphanRequests().isEmpty());
    }

    @Test
    void nginxResponseTimestampOffsetByDuration() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /slow HTTP/1.1\" 200 100 cache=MISS rt=2.500 urt=2.400 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals(2500, pair.durationMs());
        assertNotEquals(pair.requestTimestamp(), pair.responseTimestamp());
    }

    @Test
    void nginxTimestampPrecision_requestIsResponseMinusDuration() throws IOException {
        // Nginx logs at response time; requestTimestamp = responseTimestamp - duration
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:05 -0300] \"GET /api/test HTTP/1.1\" 200 100 cache=MISS rt=3.000 urt=2.500 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        // Log timestamp (response time): 10:00:05
        assertEquals(java.time.LocalDateTime.of(2026, 4, 29, 10, 0, 5), pair.responseTimestamp());
        // Request time: 10:00:05 - 3000ms = 10:00:02
        assertEquals(java.time.LocalDateTime.of(2026, 4, 29, 10, 0, 2), pair.requestTimestamp());
    }

    @Test
    void nginxZeroDuration_requestEqualsResponse() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /health HTTP/1.1\" 200 2 \"\" \"kube-probe/1.25\""
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals(0, pair.durationMs());
        assertEquals(pair.requestTimestamp(), pair.responseTimestamp());
    }

    // ---- Upstream duration extraction ----

    @Test
    void nginxUpstreamExtracted_connectionDelayComputed() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/data HTTP/1.1\" 200 100 cache=MISS rt=0.100 urt=0.060 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals(100, pair.durationMs());
        assertEquals(60, pair.upstreamDurationMs());
        assertEquals(40, pair.connectionDelayMs());
    }

    @Test
    void nginxNoUpstreamField_upstreamIsNegativeOne() throws IOException {
        // Combined format without rt/urt fields
        Path file = writeLog(
                "192.168.1.1 - frank [29/Apr/2026:10:00:00 -0300] \"GET /page HTTP/1.1\" 200 5000 \"http://ref\" \"Mozilla/5.0\""
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals(-1, pair.upstreamDurationMs());
        assertEquals(-1, pair.connectionDelayMs());
    }

    @Test
    void nginxMalformedUpstream_upstreamIsNegativeOne() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api HTTP/1.1\" 200 100 cache=- rt=0.010 urt=- resp_size=0"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals(-1, pair.upstreamDurationMs());
    }

    @Test
    void nginxUpstreamZero_delayEqualsTotal() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /cached HTTP/1.1\" 200 100 cache=HIT rt=0.050 urt=0.000 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals(0, pair.upstreamDurationMs());
        assertEquals(50, pair.connectionDelayMs());
    }

    // ---- Slow connection detection ----

    @Test
    void nginxSlowConnection_absoluteThresholdExceeded() throws IOException {
        setField("slowConnectionThresholdMs", 100);
        setField("slowConnectionPercentThreshold", 50);

        Path file = writeLog(
                // rt=0.500, urt=0.300 → delay=200ms → exceeds 100ms absolute threshold
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/slow HTTP/1.1\" 200 100 cache=MISS rt=0.500 urt=0.300 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().getFirst().slowConnection());
    }

    @Test
    void nginxSlowConnection_percentageThresholdExceeded() throws IOException {
        setField("slowConnectionThresholdMs", 100);
        setField("slowConnectionPercentThreshold", 50);

        Path file = writeLog(
                // rt=0.080, urt=0.020 → delay=60ms, pct=75% → exceeds 50% threshold (but < 100ms absolute)
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/pct HTTP/1.1\" 200 100 cache=MISS rt=0.080 urt=0.020 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().getFirst().slowConnection());
    }

    @Test
    void nginxNotSlowConnection_belowBothThresholds() throws IOException {
        setField("slowConnectionThresholdMs", 100);
        setField("slowConnectionPercentThreshold", 50);

        Path file = writeLog(
                // rt=0.200, urt=0.180 → delay=20ms (10%) → below both thresholds
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/ok HTTP/1.1\" 200 100 cache=MISS rt=0.200 urt=0.180 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertFalse(result.getApiCalls().getFirst().slowConnection());
    }

    @Test
    void nginxSlowConnection_zeroDuration_notSlow() throws IOException {
        setField("slowConnectionThresholdMs", 100);
        setField("slowConnectionPercentThreshold", 50);

        // No rt field → duration=0, no upstream → delay=-1 → not slow
        Path file = writeLog(
                "192.168.1.1 - user [29/Apr/2026:10:00:00 -0300] \"GET /health HTTP/1.1\" 200 2 \"\" \"agent\""
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertFalse(result.getApiCalls().getFirst().slowConnection());
    }

    // ---- No upstream field in preset ----

    @Test
    void nginxPresetWithoutUpstreamField_noConnectionDelay() throws IOException {
        LogPreset noUpstream = new LogPreset(
                LogPreset.NGINX.name(), LogPreset.NGINX.logLineRegex(), LogPreset.NGINX.timestampFormat(),
                LogPreset.NGINX.apiCallRegex(), null, null, null, List.of(), List.of(), List.of(),
                null  // no upstreamDurationField
        );

        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api/data HTTP/1.1\" 200 100 cache=MISS rt=0.100 urt=0.060 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), noUpstream, 1000, AnalysisOptions.all());

        ApiCallPair pair = result.getApiCalls().getFirst();
        assertEquals(100, pair.durationMs());
        assertEquals(-1, pair.upstreamDurationMs()); // upstream not extracted
    }

    // ---- hasConnectionDelay flag ----

    @Test
    void nginxAnalysis_hasConnectionDelayTrue() throws IOException {
        Path file = writeLog(
                "10.0.0.1 - [29/Apr/2026:10:00:00 -0300] \"GET /api HTTP/1.1\" 200 100 cache=HIT rt=0.010 urt=0.005 resp_size=100"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("access.log"), LogPreset.NGINX, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().stream().anyMatch(c -> c.upstreamDurationMs() >= 0));
    }

    @Test
    void nonNginxAnalysis_hasConnectionDelayFalse() throws IOException {
        Path file = writeLog(
                "2026-03-30 07:31:13,938 INFO  [stdout] (default task-1) OrderWS/getOrders Request Body: {}"
        );

        LogAnalysis result = parser.analyze(List.of(file), List.of("server.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());

        assertTrue(result.getApiCalls().stream().noneMatch(c -> c.upstreamDurationMs() >= 0));
    }

    // ---- Helpers ----

    private Path writeLog(String... lines) throws IOException {
        return writeLog(tempDir.resolve("test-" + System.nanoTime() + ".log"), lines);
    }

    private Path writeLog(Path path, String... lines) throws IOException {
        Files.writeString(path, String.join("\n", lines) + "\n");
        return path;
    }
}
