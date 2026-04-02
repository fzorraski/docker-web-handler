package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.model.NpeLocationSummary;
import br.com.fzdevx.domain.model.NpeOccurrence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NpeAnalyzerTest {

    private NpeAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new NpeAnalyzer();
        setField("enabled", true);
        setField("maxOccurrences", 5000);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = NpeAnalyzer.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(analyzer, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private LogLine line(int num, String message) {
        return new LogLine(num, LocalDateTime.of(2026, 3, 30, 10, 0, 0).plusSeconds(num), "ERROR",
                "test.Logger", "thread-1", message, "test.log");
    }

    private LogLine line(int num, LocalDateTime ts, String message) {
        return new LogLine(num, ts, "ERROR", "test.Logger", "thread-1", message, "test.log");
    }

    private LogLine continuationLine(int num, String message) {
        return new LogLine(num, null, null, null, null, message, "test.log");
    }

    // ---- Test cases ----

    @Test
    void testBasicNpeDetection() {
        var lines = List.of(
                line(1, "java.lang.NullPointerException"),
                continuationLine(2, "\tat com.example.MyService.process(MyService.java:42)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        NpeLocationSummary summary = results.getFirst();
        assertEquals("com.example.MyService", summary.originClass());
        assertEquals("process", summary.method());
        assertEquals("MyService.java", summary.sourceFile());
        assertEquals(42, summary.sourceLine());
        assertEquals(1, summary.count());
    }

    @Test
    void testCausedByPrefix() {
        var lines = List.of(
                line(1, "Caused by: java.lang.NullPointerException: msg"),
                continuationLine(2, "\tat com.example.Handler.handle(Handler.java:99)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        NpeLocationSummary summary = results.getFirst();
        assertEquals("com.example.Handler", summary.originClass());
        assertEquals("handle", summary.method());
        assertEquals("msg", summary.occurrences().getFirst().message());
    }

    @Test
    void testNpeWithoutMessage() {
        var lines = List.of(
                line(1, "java.lang.NullPointerException"),
                continuationLine(2, "\tat org.acme.Foo.bar(Foo.java:10)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("java.lang.NullPointerException", results.getFirst().occurrences().getFirst().message());
    }

    @Test
    void testNpeWithMessage() {
        var lines = List.of(
                line(1, "java.lang.NullPointerException: parameter == null"),
                continuationLine(2, "\tat org.acme.Foo.bar(Foo.java:10)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("parameter == null", results.getFirst().occurrences().getFirst().message());
    }

    @Test
    void testModulePrefixInAtLine() {
        var lines = List.of(
                line(1, "java.lang.NullPointerException"),
                continuationLine(2, "\tat deployment.ear.jar//org.example.Class.method(Class.java:42)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        NpeLocationSummary summary = results.getFirst();
        assertEquals("org.example.Class", summary.originClass());
        assertEquals("method", summary.method());
        assertEquals("Class.java", summary.sourceFile());
        assertEquals(42, summary.sourceLine());
    }

    @Test
    void testJdkModulePrefixInAtLine() {
        var lines = List.of(
                line(1, "java.lang.NullPointerException"),
                continuationLine(2, "\tat java.base/jdk.internal.reflect.Method.invoke(Method.java:566)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        NpeLocationSummary summary = results.getFirst();
        assertEquals("jdk.internal.reflect.Method", summary.originClass());
        assertEquals("invoke", summary.method());
        assertEquals("Method.java", summary.sourceFile());
        assertEquals(566, summary.sourceLine());
    }

    @Test
    void testTimestampInheritance() {
        LocalDateTime ts = LocalDateTime.of(2026, 3, 30, 14, 30, 0);

        var lines = List.of(
                line(1, ts, "INFO Some unrelated log line"),
                continuationLine(2, "java.lang.NullPointerException: inherited timestamp"),
                continuationLine(3, "\tat com.example.Svc.run(Svc.java:5)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        NpeOccurrence occ = results.getFirst().occurrences().getFirst();
        assertEquals(ts, occ.timestamp());
    }

    @Test
    void testGroupingByLocation() {
        var lines = new ArrayList<LogLine>();
        for (int i = 0; i < 3; i++) {
            int base = i * 10;
            lines.add(line(base + 1, "java.lang.NullPointerException"));
            lines.add(continuationLine(base + 2, "\tat com.example.Same.doIt(Same.java:77)"));
        }

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals(3, results.getFirst().count());
        assertEquals("Same.doIt:77", results.getFirst().origin());
    }

    @Test
    void testMultipleLocations() {
        var lines = new ArrayList<LogLine>();

        // Location A: 3 occurrences
        for (int i = 0; i < 3; i++) {
            int base = i * 10;
            lines.add(line(base + 1, "java.lang.NullPointerException"));
            lines.add(continuationLine(base + 2, "\tat com.a.A.methodA(A.java:10)"));
        }

        // Location B: 1 occurrence
        lines.add(line(31, "java.lang.NullPointerException"));
        lines.add(continuationLine(32, "\tat com.b.B.methodB(B.java:20)"));

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(2, results.size());
        // Sorted by count descending
        assertEquals(3, results.get(0).count());
        assertEquals("com.a.A", results.get(0).originClass());
        assertEquals(1, results.get(1).count());
        assertEquals("com.b.B", results.get(1).originClass());
    }

    @Test
    void testStackTraceCollected() {
        var lines = List.of(
                line(1, "java.lang.NullPointerException: oops"),
                continuationLine(2, "\tat com.example.Svc.run(Svc.java:5)"),
                continuationLine(3, "\tat com.example.Caller.invoke(Caller.java:100)"),
                continuationLine(4, "\tat java.base/jdk.internal.reflect.Method.invoke(Method.java:566)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        List<String> stackTrace = results.getFirst().occurrences().getFirst().stackTrace();
        assertEquals(3, stackTrace.size());
        assertTrue(stackTrace.get(0).contains("com.example.Svc.run"));
        assertTrue(stackTrace.get(1).contains("com.example.Caller.invoke"));
        assertTrue(stackTrace.get(2).contains("jdk.internal.reflect.Method.invoke"));
    }

    @Test
    void testFirstSeenLastSeen() {
        LocalDateTime t1 = LocalDateTime.of(2026, 3, 30, 8, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 3, 30, 12, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 3, 30, 16, 0, 0);

        var lines = List.of(
                line(1, t2, "java.lang.NullPointerException"),
                continuationLine(2, "\tat com.ex.S.m(S.java:1)"),
                line(3, t1, "java.lang.NullPointerException"),
                continuationLine(4, "\tat com.ex.S.m(S.java:1)"),
                line(5, t3, "java.lang.NullPointerException"),
                continuationLine(6, "\tat com.ex.S.m(S.java:1)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals(t1, results.getFirst().firstSeen());
        assertEquals(t3, results.getFirst().lastSeen());
    }

    @Test
    void testDisabledReturnsEmpty() {
        setField("enabled", false);

        var lines = List.of(
                line(1, "java.lang.NullPointerException"),
                continuationLine(2, "\tat com.example.Svc.run(Svc.java:5)")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertTrue(results.isEmpty());
    }

    @Test
    void testMaxOccurrencesCap() {
        setField("maxOccurrences", 3);

        var lines = new ArrayList<LogLine>();
        for (int i = 0; i < 5; i++) {
            int base = i * 10;
            lines.add(line(base + 1, "java.lang.NullPointerException"));
            lines.add(continuationLine(base + 2, "\tat com.example.S.m(S.java:1)"));
        }

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        int totalOccurrences = results.stream().mapToInt(NpeLocationSummary::count).sum();
        assertEquals(3, totalOccurrences);
    }

    @Test
    void testNoNpesReturnsEmpty() {
        var lines = List.of(
                line(1, "INFO Starting application"),
                line(2, "DEBUG Processing request"),
                line(3, "INFO Request completed successfully")
        );

        List<NpeLocationSummary> results = analyzer.analyze(lines);

        assertTrue(results.isEmpty());
    }
}
