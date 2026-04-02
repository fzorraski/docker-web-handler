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

    // ---- Helpers ----

    private Path writeLog(String... lines) throws IOException {
        return writeLog(tempDir.resolve("test-" + System.nanoTime() + ".log"), lines);
    }

    private Path writeLog(Path path, String... lines) throws IOException {
        Files.writeString(path, String.join("\n", lines) + "\n");
        return path;
    }
}
