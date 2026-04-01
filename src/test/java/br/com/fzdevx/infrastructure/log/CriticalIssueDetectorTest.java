package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.CriticalBurst;
import br.com.fzdevx.domain.model.CriticalIssue;
import br.com.fzdevx.domain.model.CriticalIssueSummary;
import br.com.fzdevx.domain.model.LogLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CriticalIssueDetectorTest {

    private CriticalIssueDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CriticalIssueDetector();
        setField("enabled", true);
        setField("javaPatterns", true);
        setField("burstThreshold", 10);
        setField("burstWindowMinutes", 5);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = CriticalIssueDetector.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(detector, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private LogLine line(int num, String message) {
        return new LogLine(num, LocalDateTime.of(2026, 3, 30, 10, 0, 0).plusSeconds(num), "ERROR",
                "test.Logger", "thread-1", message, "test.log");
    }

    // ---- Generic patterns ----

    @Test
    void detectsOomKill() {
        var lines = List.of(line(1, "Out of memory: Kill process 1234"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("OOM", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
        assertEquals(1, results.getFirst().count());
    }

    @Test
    void detectsSegfault() {
        var lines = List.of(line(1, "kernel: app[1234]: segfault at 0x0000"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("SEGFAULT", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsDiskFull() {
        var lines = List.of(line(1, "No space left on device"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("DISK", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    @Test
    void detectsConnectionRefused() {
        var lines = List.of(line(1, "Connection refused to host db-server:5432"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("NETWORK", results.getFirst().category());
        assertEquals("MEDIUM", results.getFirst().severity());
    }

    @Test
    void detectsConnectionTimedOut() {
        var lines = List.of(line(1, "Connection timed out after 30000ms"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("NETWORK", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    @Test
    void detectsReadTimedOut() {
        var lines = List.of(line(1, "Read timed out on socket"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("NETWORK", results.getFirst().category());
        assertEquals("MEDIUM", results.getFirst().severity());
    }

    // ---- Java OOM patterns ----

    @Test
    void detectsOutOfMemoryError() {
        var lines = List.of(line(1, "java.lang.OutOfMemoryError: Java heap space"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("OOM", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsGcOverhead() {
        var lines = List.of(line(1, "GC overhead limit exceeded"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("OOM", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsDirectBufferMemory() {
        var lines = List.of(line(1, "Direct buffer memory allocation failed"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("OOM", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    // ---- JDBC patterns ----

    @Test
    void detectsJdbcConnectionException() {
        var lines = List.of(line(1, "org.hibernate.exception.JDBCConnectionException: unable to obtain connection"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsConnectionClosed() {
        var lines = List.of(line(1, "This connection has been closed"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
    }

    @Test
    void detectsUnableToAcquireJdbcConnection() {
        var lines = List.of(line(1, "Unable to acquire JDBC Connection from pool"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
    }

    @Test
    void detectsPsqlException() {
        var lines = List.of(line(1, "org.postgresql.util.PSQLException: FATAL: too many connections"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    // ---- Thread patterns ----

    @Test
    void detectsStackOverflowError() {
        var lines = List.of(line(1, "java.lang.StackOverflowError in recursive call"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("THREAD", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    @Test
    void detectsRejectedExecutionException() {
        var lines = List.of(line(1, "java.util.concurrent.RejectedExecutionException: Task rejected"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("THREAD", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    @Test
    void detectsDeadlock() {
        var lines = List.of(line(1, "Found one Java-level deadlock"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("THREAD", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    // ---- ClassLoading patterns ----

    @Test
    void detectsClassNotFoundException() {
        var lines = List.of(line(1, "java.lang.ClassNotFoundException: com.example.MyClass"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("CLASSLOADING", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    @Test
    void detectsNoClassDefFoundError() {
        var lines = List.of(line(1, "java.lang.NoClassDefFoundError: com/example/Missing"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("CLASSLOADING", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    // ---- WildFly patterns ----

    @Test
    void detectsWflysrv0056() {
        var lines = List.of(line(1, "WFLYSRV0056: Server boot has failed"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("DEPLOYMENT", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsIj000453() {
        var lines = List.of(line(1, "IJ000453: Unable to get managed connection for datasource"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsWflyejb0034() {
        var lines = List.of(line(1, "WFLYEJB0034: EJB Invocation failed"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("EJB", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    @Test
    void detectsUt005023() {
        var lines = List.of(line(1, "UT005023: Exception handling request"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("UNDERTOW", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    @Test
    void detectsIspn000299() {
        var lines = List.of(line(1, "ISPN000299: Unable to acquire lock"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("CLUSTER", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    // ---- Quarkus patterns ----

    @Test
    void detectsFailedToStartApplication() {
        var lines = List.of(line(1, "Failed to start application: initialization error"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("DEPLOYMENT", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsAgroal000007() {
        var lines = List.of(line(1, "AGROAL000007: Datasource connection pool exhausted"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsThreadBlocked() {
        var lines = List.of(line(1, "Thread blocked on IO operation for 5s"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("REACTIVE", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    // ---- Spring Boot patterns ----

    @Test
    void detectsApplicationFailedToStart() {
        var lines = List.of(line(1, "***************************\nAPPLICATION FAILED TO START\n***************************"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("DEPLOYMENT", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsHikariPoolExhaustion() {
        var lines = List.of(line(1, "HikariPool-1 - Connection is not available, request timed out after 30000ms"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsHikariValidationFailure() {
        var lines = List.of(line(1, "HikariPool-1 - Failed to validate connection com.mysql.cj.jdbc.ConnectionImpl@abc"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsHikariConnectionLeak() {
        var lines = List.of(line(1, "HikariPool-1 - Apparent connection leak detected"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("JDBC", results.getFirst().category());
        assertEquals("CRITICAL", results.getFirst().severity());
    }

    @Test
    void detectsLivenessStateBroken() {
        var lines = List.of(line(1, "LivenessState changed to BROKEN"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("HEALTH", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
    }

    // ---- Disabled returns empty ----

    @Test
    void returnsEmptyWhenDisabled() {
        setField("enabled", false);
        var lines = List.of(line(1, "OutOfMemoryError"), line(2, "segfault"));
        List<CriticalIssueSummary> results = detector.detect(lines);

        assertTrue(results.isEmpty());
    }

    // ---- Java patterns disabled ----

    @Test
    void returnsOnlyGenericPatternsWhenJavaPatternsDisabled() {
        setField("javaPatterns", false);

        var lines = List.of(
                line(1, "OutOfMemoryError"),           // Java-only, should be skipped
                line(2, "Connection refused"),          // Generic, should match
                line(3, "StackOverflowError"),          // Java-only, should be skipped
                line(4, "Out of memory: Kill process")  // Generic, should match
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(2, results.size());
        var categories = results.stream().map(CriticalIssueSummary::category).toList();
        assertTrue(categories.contains("NETWORK"));
        assertTrue(categories.contains("OOM"));

        // OOM category should only have the generic match, not the Java OutOfMemoryError
        CriticalIssueSummary oomSummary = results.stream()
                .filter(s -> "OOM".equals(s.category()))
                .findFirst().orElseThrow();
        assertEquals(1, oomSummary.count());
        assertTrue(oomSummary.issues().getFirst().message().contains("Out of memory: Kill"));
    }

    // ---- Groups by category ----

    @Test
    void groupsByCategory() {
        var lines = List.of(
                line(1, "OutOfMemoryError: Java heap space"),
                line(2, "GC overhead limit exceeded"),
                line(3, "Connection refused to db"),
                line(4, "StackOverflowError in recursion")
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(3, results.size());

        CriticalIssueSummary oom = results.stream().filter(s -> "OOM".equals(s.category())).findFirst().orElseThrow();
        assertEquals(2, oom.count());

        CriticalIssueSummary network = results.stream().filter(s -> "NETWORK".equals(s.category())).findFirst().orElseThrow();
        assertEquals(1, network.count());

        CriticalIssueSummary thread = results.stream().filter(s -> "THREAD".equals(s.category())).findFirst().orElseThrow();
        assertEquals(1, thread.count());
    }

    // ---- Severity escalation ----

    @Test
    void severityEscalatesToHighestInCategory() {
        var lines = List.of(
                line(1, "Connection refused to server"),    // NETWORK / MEDIUM
                line(2, "Connection timed out after 30s")   // NETWORK / HIGH
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("NETWORK", results.getFirst().category());
        assertEquals("HIGH", results.getFirst().severity());
        assertEquals(2, results.getFirst().count());
    }

    // ---- Match cap at 10,000 ----

    @Test
    void matchesCappedAtMaxTotal() {
        var lines = new ArrayList<LogLine>();
        for (int i = 0; i < 10_100; i++) {
            lines.add(line(i + 1, "Connection refused attempt " + i));
        }

        List<CriticalIssueSummary> results = detector.detect(lines);

        int totalMatches = results.stream().mapToInt(CriticalIssueSummary::count).sum();
        assertEquals(10_000, totalMatches);
    }

    // ---- Lines without timestamps ----

    @Test
    void linesWithoutTimestampsHandled() {
        var lines = List.of(
                new LogLine(1, null, "ERROR", "test.Logger", "thread-1", "OutOfMemoryError", "test.log")
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals("OOM", results.getFirst().category());
        assertNull(results.getFirst().firstSeen());
        assertNull(results.getFirst().lastSeen());
    }

    // ---- Continuation / stack trace lines ----

    @Test
    void continuationLinesWithNullMessageSkipped() {
        var lines = List.of(
                new LogLine(1, null, null, null, null, null, "test.log"),
                new LogLine(2, null, null, null, null, "", "test.log"),
                line(3, "OutOfMemoryError: heap space")
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().count());
        assertEquals(3, results.getFirst().issues().getFirst().lineNumber());
    }

    // ---- firstSeen / lastSeen ----

    @Test
    void firstSeenLastSeenComputedCorrectly() {
        LocalDateTime t1 = LocalDateTime.of(2026, 3, 30, 8, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 3, 30, 12, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 3, 30, 16, 0, 0);

        var lines = List.of(
                new LogLine(1, t2, "ERROR", "test.Logger", "thread-1", "OutOfMemoryError mid", "test.log"),
                new LogLine(2, t1, "ERROR", "test.Logger", "thread-1", "OutOfMemoryError early", "test.log"),
                new LogLine(3, t3, "ERROR", "test.Logger", "thread-1", "OutOfMemoryError late", "test.log")
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals(t1, results.getFirst().firstSeen());
        assertEquals(t3, results.getFirst().lastSeen());
    }

    @Test
    void firstSeenLastSeenIgnoresNullTimestamps() {
        LocalDateTime t1 = LocalDateTime.of(2026, 3, 30, 10, 0, 0);

        var lines = List.of(
                new LogLine(1, null, "ERROR", "test.Logger", "thread-1", "OutOfMemoryError no time", "test.log"),
                new LogLine(2, t1, "ERROR", "test.Logger", "thread-1", "GC overhead limit exceeded", "test.log")
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertEquals(1, results.size());
        assertEquals(t1, results.getFirst().firstSeen());
        assertEquals(t1, results.getFirst().lastSeen());
    }

    // ---- Empty input ----

    @Test
    void emptyInputReturnsEmpty() {
        List<CriticalIssueSummary> results = detector.detect(List.of());
        assertTrue(results.isEmpty());
    }

    // ---- No matches returns empty ----

    @Test
    void noMatchesReturnsEmpty() {
        var lines = List.of(
                line(1, "INFO Starting application"),
                line(2, "DEBUG Processing request"),
                line(3, "INFO Request completed successfully")
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        assertTrue(results.isEmpty());
    }

    // ---- One match per line ----

    @Test
    void onlyOneMatchPerLine() {
        // This line contains both OOM and JDBC patterns, but only one should match
        var lines = List.of(
                line(1, "OutOfMemoryError while processing JDBCConnectionException")
        );

        List<CriticalIssueSummary> results = detector.detect(lines);

        int totalMatches = results.stream().mapToInt(CriticalIssueSummary::count).sum();
        assertEquals(1, totalMatches);
    }

    // ---- Burst Detection ----

    private CriticalIssue issue(String category, String severity, String pattern, LocalDateTime ts) {
        return new CriticalIssue(category, severity, pattern, 1, ts, "test message", "test.log");
    }

    private CriticalIssueSummary summary(String category, String severity, List<CriticalIssue> issues) {
        LocalDateTime first = issues.stream().map(CriticalIssue::timestamp).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime last = issues.stream().map(CriticalIssue::timestamp).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new CriticalIssueSummary(category, severity, issues.size(), first, last, issues, List.of());
    }

    @Test
    void testNoBurstsWhenBelowThreshold() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 30, 10, 0, 0);
        List<CriticalIssue> issues = IntStream.range(0, 5)
                .mapToObj(i -> issue("JDBC", "CRITICAL", "JDBCConnectionException", base.plusSeconds(i * 10)))
                .toList();

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s));

        assertEquals(1, results.size());
        assertTrue(results.getFirst().bursts().isEmpty());
    }

    @Test
    void testBurstDetectedWhenThresholdMet() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 30, 10, 0, 0);
        List<CriticalIssue> issues = IntStream.range(0, 10)
                .mapToObj(i -> issue("JDBC", "CRITICAL", "JDBCConnectionException", base.plusSeconds(i * 12)))
                .toList();

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s));

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().bursts().size());
        assertEquals(10, results.getFirst().bursts().getFirst().issueCount());
    }

    @Test
    void testBurstDetectedWithCustomThreshold() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 30, 10, 0, 0);
        List<CriticalIssue> issues = IntStream.range(0, 5)
                .mapToObj(i -> issue("JDBC", "CRITICAL", "JDBCConnectionException", base.plusSeconds(i * 10)))
                .toList();

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s), 3, 5);

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().bursts().size());
        assertEquals(5, results.getFirst().bursts().getFirst().issueCount());
    }

    @Test
    void testBurstDetectedWithCustomWindow() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 30, 10, 0, 0);
        // 10 issues spread over 10 minutes (1 per minute)
        List<CriticalIssue> issues = IntStream.range(0, 10)
                .mapToObj(i -> issue("JDBC", "CRITICAL", "JDBCConnectionException", base.plusMinutes(i)))
                .toList();

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);

        // With window=15 and threshold=5, all 10 fit in one window -> 1 burst
        List<CriticalIssueSummary> wideWindow = detector.computeBursts(List.of(s), 5, 15);
        assertEquals(1, wideWindow.getFirst().bursts().size());

        // With window=2 and threshold=5, at most 3 issues fit in a 2-minute window -> 0 bursts
        List<CriticalIssueSummary> narrowWindow = detector.computeBursts(List.of(s), 5, 2);
        assertTrue(narrowWindow.getFirst().bursts().isEmpty());
    }

    @Test
    void testMultipleBurstsInSameCategory() {
        LocalDateTime base1 = LocalDateTime.of(2026, 3, 30, 0, 0, 0);
        LocalDateTime base2 = LocalDateTime.of(2026, 3, 30, 0, 30, 0);

        List<CriticalIssue> issues = new ArrayList<>();
        // First cluster: 10 issues at 00:00 - 00:02
        for (int i = 0; i < 10; i++) {
            issues.add(issue("JDBC", "CRITICAL", "JDBCConnectionException", base1.plusSeconds(i * 12)));
        }
        // Second cluster: 10 issues at 00:30 - 00:32
        for (int i = 0; i < 10; i++) {
            issues.add(issue("JDBC", "CRITICAL", "JDBCConnectionException", base2.plusSeconds(i * 12)));
        }

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s));

        assertEquals(1, results.size());
        assertEquals(2, results.getFirst().bursts().size());
        assertEquals(10, results.getFirst().bursts().get(0).issueCount());
        assertEquals(10, results.getFirst().bursts().get(1).issueCount());
    }

    @Test
    void testAdjacentBurstsMerged() {
        LocalDateTime base1 = LocalDateTime.of(2026, 3, 30, 0, 0, 0);
        // Second cluster starts at 00:06 — beyond the 5-min detection window from 00:00
        // but gap from first burst end (00:01:48) to 00:06:00 is ~4 min 12 sec, <= window(5), so merge happens
        LocalDateTime base2 = LocalDateTime.of(2026, 3, 30, 0, 6, 0);

        List<CriticalIssue> issues = new ArrayList<>();
        // First cluster: 10 issues at 00:00:00 - 00:01:48 (12s apart)
        for (int i = 0; i < 10; i++) {
            issues.add(issue("JDBC", "CRITICAL", "JDBCConnectionException", base1.plusSeconds(i * 12)));
        }
        // Second cluster: 10 issues at 00:06:00 - 00:07:48 (12s apart)
        for (int i = 0; i < 10; i++) {
            issues.add(issue("JDBC", "CRITICAL", "JDBCConnectionException", base2.plusSeconds(i * 12)));
        }

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s));

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().bursts().size());
        assertEquals(20, results.getFirst().bursts().getFirst().issueCount());
    }

    @Test
    void testBurstsAcrossCategories() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 30, 10, 0, 0);

        List<CriticalIssue> jdbcIssues = IntStream.range(0, 10)
                .mapToObj(i -> issue("JDBC", "CRITICAL", "JDBCConnectionException", base.plusSeconds(i * 10)))
                .toList();
        List<CriticalIssue> oomIssues = IntStream.range(0, 10)
                .mapToObj(i -> issue("OOM", "CRITICAL", "OutOfMemoryError", base.plusSeconds(i * 10)))
                .toList();

        CriticalIssueSummary jdbcSummary = summary("JDBC", "CRITICAL", jdbcIssues);
        CriticalIssueSummary oomSummary = summary("OOM", "CRITICAL", oomIssues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(jdbcSummary, oomSummary));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(s -> s.bursts().size() == 1));

        CriticalIssueSummary jdbc = results.stream().filter(s -> "JDBC".equals(s.category())).findFirst().orElseThrow();
        assertEquals("JDBC", jdbc.bursts().getFirst().category());
        assertEquals(10, jdbc.bursts().getFirst().issueCount());

        CriticalIssueSummary oom = results.stream().filter(s -> "OOM".equals(s.category())).findFirst().orElseThrow();
        assertEquals("OOM", oom.bursts().getFirst().category());
        assertEquals(10, oom.bursts().getFirst().issueCount());
    }

    @Test
    void testIssuesWithoutTimestampsExcludedFromBursts() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 30, 10, 0, 0);

        List<CriticalIssue> issues = new ArrayList<>();
        // 10 issues with timestamps (forming a burst)
        for (int i = 0; i < 10; i++) {
            issues.add(issue("JDBC", "CRITICAL", "JDBCConnectionException", base.plusSeconds(i * 10)));
        }
        // 5 issues without timestamps
        for (int i = 0; i < 5; i++) {
            issues.add(issue("JDBC", "CRITICAL", "JDBCConnectionException", null));
        }

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s));

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().bursts().size());
        assertEquals(10, results.getFirst().bursts().getFirst().issueCount());
    }

    @Test
    void testBurstStartAndEndTimestamps() {
        LocalDateTime first = LocalDateTime.of(2026, 3, 30, 10, 0, 0);
        LocalDateTime last = LocalDateTime.of(2026, 3, 30, 10, 1, 30);

        List<CriticalIssue> issues = IntStream.range(0, 10)
                .mapToObj(i -> issue("JDBC", "CRITICAL", "JDBCConnectionException", first.plusSeconds(i * 10)))
                .toList();

        CriticalIssueSummary s = summary("JDBC", "CRITICAL", issues);
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s));

        assertEquals(1, results.size());
        CriticalBurst burst = results.getFirst().bursts().getFirst();
        assertEquals(first, burst.burstStart());
        assertEquals(last, burst.burstEnd());
    }

    @Test
    void testEmptyIssuesProducesNoBursts() {
        CriticalIssueSummary s = summary("JDBC", "CRITICAL", List.of());
        List<CriticalIssueSummary> results = detector.computeBursts(List.of(s));

        assertEquals(1, results.size());
        assertTrue(results.getFirst().bursts().isEmpty());
    }
}
