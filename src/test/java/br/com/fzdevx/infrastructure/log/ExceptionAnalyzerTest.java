package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.ExceptionLocationSummary;
import br.com.fzdevx.domain.model.ExceptionOccurrence;
import br.com.fzdevx.domain.model.LogLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionAnalyzerTest {

    private ExceptionAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ExceptionAnalyzer();
        setField("enabled", true);
        setField("maxOccurrences", 5000);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = ExceptionAnalyzer.class.getDeclaredField(name);
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

    // ---- Basic exception detection ----

    @Test
    void detectsClassCastException() {
        var lines = List.of(
                line(1, "java.lang.ClassCastException: String cannot be cast to Integer"),
                continuationLine(2, "\tat com.example.Service.convert(Service.java:55)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        ExceptionLocationSummary s = results.getFirst();
        assertEquals("ClassCastException", s.exceptionType());
        assertEquals("com.example.Service", s.originClass());
        assertEquals("convert", s.method());
        assertEquals("Service.java", s.sourceFile());
        assertEquals(55, s.sourceLine());
        assertEquals(1, s.count());
        assertEquals("String cannot be cast to Integer", s.occurrences().getFirst().message());
    }

    @Test
    void detectsIllegalArgumentException() {
        var lines = List.of(
                line(1, "java.lang.IllegalArgumentException: timeout must be positive"),
                continuationLine(2, "\tat com.example.Config.validate(Config.java:30)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("IllegalArgumentException", results.getFirst().exceptionType());
        assertEquals("com.example.Config", results.getFirst().originClass());
    }

    @Test
    void detectsIllegalStateException() {
        var lines = List.of(
                line(1, "java.lang.IllegalStateException: already initialized"),
                continuationLine(2, "\tat com.example.App.init(App.java:10)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("IllegalStateException", results.getFirst().exceptionType());
    }

    @Test
    void detectsConcurrentModificationException() {
        var lines = List.of(
                line(1, "java.util.ConcurrentModificationException"),
                continuationLine(2, "\tat java.base/java.util.HashMap.forEach(HashMap.java:1337)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("ConcurrentModificationException", results.getFirst().exceptionType());
    }

    @Test
    void detectsArrayIndexOutOfBoundsException() {
        var lines = List.of(
                line(1, "java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 3"),
                continuationLine(2, "\tat com.example.Processor.process(Processor.java:42)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("ArrayIndexOutOfBoundsException", results.getFirst().exceptionType());
    }

    @Test
    void detectsNumberFormatException() {
        var lines = List.of(
                line(1, "java.lang.NumberFormatException: For input string: \"abc\""),
                continuationLine(2, "\tat com.example.Parser.parseInt(Parser.java:20)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("NumberFormatException", results.getFirst().exceptionType());
        assertEquals("For input string: \"abc\"", results.getFirst().occurrences().getFirst().message());
    }

    @Test
    void detectsSQLException() {
        var lines = List.of(
                line(1, "java.sql.SQLException: Connection refused"),
                continuationLine(2, "\tat com.example.Dao.query(Dao.java:100)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("SQLException", results.getFirst().exceptionType());
    }

    @Test
    void detectsTimeoutException() {
        var lines = List.of(
                line(1, "java.util.concurrent.TimeoutException"),
                continuationLine(2, "\tat com.example.Client.call(Client.java:77)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("TimeoutException", results.getFirst().exceptionType());
    }

    @Test
    void detectsSecurityException() {
        var lines = List.of(
                line(1, "java.lang.SecurityException: access denied"),
                continuationLine(2, "\tat com.example.Guard.check(Guard.java:15)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("SecurityException", results.getFirst().exceptionType());
    }

    @Test
    void detectsFileNotFoundException() {
        var lines = List.of(
                line(1, "java.io.FileNotFoundException: /tmp/missing.txt"),
                continuationLine(2, "\tat com.example.Reader.open(Reader.java:33)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("FileNotFoundException", results.getFirst().exceptionType());
    }

    @Test
    void detectsUnsupportedOperationException() {
        var lines = List.of(
                line(1, "java.lang.UnsupportedOperationException: not implemented"),
                continuationLine(2, "\tat com.example.Stub.doWork(Stub.java:8)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("UnsupportedOperationException", results.getFirst().exceptionType());
    }

    @Test
    void detectsArithmeticException() {
        var lines = List.of(
                line(1, "java.lang.ArithmeticException: / by zero"),
                continuationLine(2, "\tat com.example.Calc.divide(Calc.java:12)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("ArithmeticException", results.getFirst().exceptionType());
        assertEquals("/ by zero", results.getFirst().occurrences().getFirst().message());
    }

    @Test
    void detectsInterruptedException() {
        var lines = List.of(
                line(1, "java.lang.InterruptedException: sleep interrupted"),
                continuationLine(2, "\tat com.example.Worker.run(Worker.java:45)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("InterruptedException", results.getFirst().exceptionType());
    }

    @Test
    void detectsStringIndexOutOfBoundsException() {
        var lines = List.of(
                line(1, "java.lang.StringIndexOutOfBoundsException: Range [0, 10) out of bounds for length 5"),
                continuationLine(2, "\tat com.example.TextUtil.sub(TextUtil.java:22)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("StringIndexOutOfBoundsException", results.getFirst().exceptionType());
    }

    @Test
    void detectsIndexOutOfBoundsException() {
        var lines = List.of(
                line(1, "java.lang.IndexOutOfBoundsException: Index: 5, Size: 3"),
                continuationLine(2, "\tat com.example.ListHelper.get(ListHelper.java:18)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("IndexOutOfBoundsException", results.getFirst().exceptionType());
    }

    // ---- IOException requires "Caused by:" prefix ----

    @Test
    void detectsIOExceptionOnlyWithCausedByPrefix() {
        var lines = List.of(
                line(1, "Caused by: java.io.IOException: Broken pipe"),
                continuationLine(2, "\tat com.example.NetService.send(NetService.java:88)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("IOException", results.getFirst().exceptionType());
        assertEquals("Broken pipe", results.getFirst().occurrences().getFirst().message());
    }

    @Test
    void ignoresIOExceptionWithoutCausedByPrefix() {
        var lines = List.of(
                line(1, "java.io.IOException: Connection reset"),
                continuationLine(2, "\tat com.example.NetService.send(NetService.java:88)")
        );

        var results = analyzer.analyze(lines);

        assertTrue(results.isEmpty());
    }

    // ---- NullPointerException is excluded ----

    @Test
    void skipsNullPointerException() {
        var lines = List.of(
                line(1, "java.lang.NullPointerException"),
                continuationLine(2, "\tat com.example.Svc.run(Svc.java:5)")
        );

        var results = analyzer.analyze(lines);

        assertTrue(results.isEmpty());
    }

    @Test
    void skipsNullPointerExceptionEvenWithMessage() {
        var lines = List.of(
                line(1, "Caused by: java.lang.NullPointerException: field was null"),
                continuationLine(2, "\tat com.example.Svc.run(Svc.java:5)")
        );

        var results = analyzer.analyze(lines);

        assertTrue(results.isEmpty());
    }

    // ---- Caused by prefix ----

    @Test
    void detectsCausedByPrefix() {
        var lines = List.of(
                line(1, "Caused by: java.lang.IllegalStateException: already closed"),
                continuationLine(2, "\tat com.example.Pool.acquire(Pool.java:77)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("IllegalStateException", results.getFirst().exceptionType());
        assertEquals("already closed", results.getFirst().occurrences().getFirst().message());
    }

    // ---- Exception without message ----

    @Test
    void detectsExceptionWithoutMessage() {
        var lines = List.of(
                line(1, "java.lang.IllegalArgumentException"),
                continuationLine(2, "\tat com.example.Validator.check(Validator.java:18)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertNull(results.getFirst().occurrences().getFirst().message());
    }

    // ---- Stack trace collection ----

    @Test
    void collectsFullStackTrace() {
        var lines = List.of(
                line(1, "java.lang.ClassCastException: bad cast"),
                continuationLine(2, "\tat com.example.A.method(A.java:10)"),
                continuationLine(3, "\tat com.example.B.caller(B.java:20)"),
                continuationLine(4, "\tat java.base/jdk.internal.reflect.Method.invoke(Method.java:566)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        List<String> trace = results.getFirst().occurrences().getFirst().stackTrace();
        assertEquals(3, trace.size());
        assertTrue(trace.get(0).contains("com.example.A.method"));
        assertTrue(trace.get(1).contains("com.example.B.caller"));
        assertTrue(trace.get(2).contains("jdk.internal.reflect.Method.invoke"));
    }

    @Test
    void collectsCausedByInStackTrace() {
        var lines = List.of(
                line(1, "java.lang.IllegalStateException: wrapper"),
                continuationLine(2, "\tat com.example.Outer.run(Outer.java:5)"),
                continuationLine(3, "Caused by: java.lang.SecurityException: inner"),
                continuationLine(4, "\tat com.example.Inner.check(Inner.java:8)")
        );

        var results = analyzer.analyze(lines);

        // The first exception detected is IllegalStateException
        assertEquals(2, results.size());
        List<String> trace = results.get(0).occurrences().getFirst().stackTrace();
        assertTrue(trace.stream().anyMatch(l -> l.contains("Caused by:")));
    }

    @Test
    void collectsEllipsisInStackTrace() {
        var lines = List.of(
                line(1, "java.lang.ClassCastException: test"),
                continuationLine(2, "\tat com.example.A.m(A.java:1)"),
                continuationLine(3, "..."),
                continuationLine(4, "\tat com.example.B.m(B.java:2)")
        );

        var results = analyzer.analyze(lines);

        List<String> trace = results.getFirst().occurrences().getFirst().stackTrace();
        assertEquals(3, trace.size());
        assertEquals("...", trace.get(1));
    }

    // ---- Exception without stack trace ----

    @Test
    void detectsExceptionWithoutStackTrace() {
        var lines = List.of(
                line(1, "java.lang.IllegalArgumentException: bad input"),
                line(2, "INFO Normal log line after exception")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        ExceptionLocationSummary s = results.getFirst();
        assertEquals("IllegalArgumentException", s.exceptionType());
        assertEquals("Unknown", s.origin());
        assertEquals("Unknown", s.originClass());
        assertEquals("unknown", s.method());
        assertTrue(s.occurrences().getFirst().stackTrace().isEmpty());
    }

    // ---- Module prefix in at-line ----

    @Test
    void handlesModulePrefixInAtLine() {
        var lines = List.of(
                line(1, "java.lang.IllegalStateException"),
                continuationLine(2, "\tat deployment.myapp.ear//com.example.Handler.handle(Handler.java:42)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("com.example.Handler", results.getFirst().originClass());
        assertEquals("handle", results.getFirst().method());
        assertEquals("Handler.java", results.getFirst().sourceFile());
    }

    @Test
    void handlesJdkModulePrefixInAtLine() {
        var lines = List.of(
                line(1, "java.util.ConcurrentModificationException"),
                continuationLine(2, "\tat java.base/java.util.HashMap$HashIterator.nextNode(HashMap.java:1597)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("java.util.HashMap$HashIterator", results.getFirst().originClass());
        assertEquals("nextNode", results.getFirst().method());
    }

    // ---- Grouping by type and location ----

    @Test
    void groupsSameExceptionSameLocation() {
        var lines = new ArrayList<LogLine>();
        for (int i = 0; i < 3; i++) {
            int base = i * 10;
            lines.add(line(base + 1, "java.lang.IllegalArgumentException: bad"));
            lines.add(continuationLine(base + 2, "\tat com.example.V.check(V.java:10)"));
        }

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals(3, results.getFirst().count());
        assertEquals("V.check:10", results.getFirst().origin());
    }

    @Test
    void separatesDifferentExceptionTypesSameLocation() {
        var lines = List.of(
                line(1, "java.lang.IllegalArgumentException: arg"),
                continuationLine(2, "\tat com.example.V.check(V.java:10)"),
                line(3, "java.lang.IllegalStateException: state"),
                continuationLine(4, "\tat com.example.V.check(V.java:10)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(s -> "IllegalArgumentException".equals(s.exceptionType())));
        assertTrue(results.stream().anyMatch(s -> "IllegalStateException".equals(s.exceptionType())));
    }

    @Test
    void separatesSameExceptionDifferentLocations() {
        var lines = List.of(
                line(1, "java.lang.IllegalArgumentException: from A"),
                continuationLine(2, "\tat com.example.A.validate(A.java:10)"),
                line(3, "java.lang.IllegalArgumentException: from B"),
                continuationLine(4, "\tat com.example.B.validate(B.java:20)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(2, results.size());
    }

    // ---- Sorting by count descending ----

    @Test
    void sortsByCountDescending() {
        var lines = new ArrayList<LogLine>();

        // Location A: 1 occurrence
        lines.add(line(1, "java.lang.ClassCastException: once"));
        lines.add(continuationLine(2, "\tat com.a.A.m(A.java:1)"));

        // Location B: 3 occurrences
        for (int i = 0; i < 3; i++) {
            int base = 10 + i * 10;
            lines.add(line(base, "java.lang.ClassCastException: many"));
            lines.add(continuationLine(base + 1, "\tat com.b.B.m(B.java:2)"));
        }

        var results = analyzer.analyze(lines);

        assertEquals(2, results.size());
        assertEquals(3, results.get(0).count());
        assertEquals(1, results.get(1).count());
    }

    // ---- Timestamp handling ----

    @Test
    void tracksFirstSeenLastSeen() {
        LocalDateTime t1 = LocalDateTime.of(2026, 3, 30, 8, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 3, 30, 12, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 3, 30, 16, 0, 0);

        var lines = List.of(
                line(1, t2, "java.lang.IllegalStateException"),
                continuationLine(2, "\tat com.ex.S.m(S.java:1)"),
                line(3, t1, "java.lang.IllegalStateException"),
                continuationLine(4, "\tat com.ex.S.m(S.java:1)"),
                line(5, t3, "java.lang.IllegalStateException"),
                continuationLine(6, "\tat com.ex.S.m(S.java:1)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals(t1, results.getFirst().firstSeen());
        assertEquals(t3, results.getFirst().lastSeen());
    }

    @Test
    void inheritsTimestampFromPrecedingLine() {
        LocalDateTime ts = LocalDateTime.of(2026, 3, 30, 14, 30, 0);

        var lines = List.of(
                line(1, ts, "INFO Some context log line"),
                continuationLine(2, "java.lang.ClassCastException: inherited timestamp"),
                continuationLine(3, "\tat com.example.Svc.run(Svc.java:5)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals(ts, results.getFirst().occurrences().getFirst().timestamp());
    }

    @Test
    void handlesNullTimestampsInFirstLastSeen() {
        var lines = List.of(
                continuationLine(1, "java.lang.IllegalArgumentException: no timestamp"),
                continuationLine(2, "\tat com.example.Svc.run(Svc.java:5)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertNull(results.getFirst().firstSeen());
        assertNull(results.getFirst().lastSeen());
    }

    // ---- Configuration ----

    @Test
    void disabledReturnsEmpty() {
        setField("enabled", false);

        var lines = List.of(
                line(1, "java.lang.IllegalArgumentException: should not detect"),
                continuationLine(2, "\tat com.example.Svc.run(Svc.java:5)")
        );

        var results = analyzer.analyze(lines);

        assertTrue(results.isEmpty());
    }

    @Test
    void maxOccurrencesCap() {
        setField("maxOccurrences", 3);

        var lines = new ArrayList<LogLine>();
        for (int i = 0; i < 5; i++) {
            int base = i * 10;
            lines.add(line(base + 1, "java.lang.ClassCastException: cast " + i));
            lines.add(continuationLine(base + 2, "\tat com.example.S.m(S.java:1)"));
        }

        var results = analyzer.analyze(lines);

        int totalOccurrences = results.stream().mapToInt(ExceptionLocationSummary::count).sum();
        assertEquals(3, totalOccurrences);
    }

    // ---- Edge cases ----

    @Test
    void noExceptionsReturnsEmpty() {
        var lines = List.of(
                line(1, "INFO Starting application"),
                line(2, "DEBUG Processing request"),
                line(3, "INFO Request completed")
        );

        var results = analyzer.analyze(lines);

        assertTrue(results.isEmpty());
    }

    @Test
    void nullInputReturnsEmpty() {
        assertTrue(analyzer.analyze(null).isEmpty());
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(analyzer.analyze(List.of()).isEmpty());
    }

    @Test
    void skipsLinesWithNullMessage() {
        var lines = List.of(
                new LogLine(1, LocalDateTime.now(), "ERROR", "test", "t-1", null, "test.log"),
                line(2, "java.lang.IllegalArgumentException: found"),
                continuationLine(3, "\tat com.example.S.m(S.java:1)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals("IllegalArgumentException", results.getFirst().exceptionType());
    }

    @Test
    void stackTraceStopsAtNullMessage() {
        var lines = List.of(
                line(1, "java.lang.ClassCastException: test"),
                continuationLine(2, "\tat com.example.A.m(A.java:1)"),
                new LogLine(3, null, null, null, null, null, "test.log"),
                continuationLine(4, "\tat com.example.B.m(B.java:2)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().occurrences().getFirst().stackTrace().size());
    }

    @Test
    void preservesLogLineMetadata() {
        var lines = List.of(
                line(42, "java.lang.IllegalArgumentException: metadata test"),
                continuationLine(43, "\tat com.example.S.m(S.java:1)")
        );

        var results = analyzer.analyze(lines);

        ExceptionOccurrence occ = results.getFirst().occurrences().getFirst();
        assertEquals(42, occ.logLineNumber());
        assertEquals("test.log", occ.logSourceFile());
    }

    @Test
    void detectsMultipleDifferentExceptionTypes() {
        var lines = List.of(
                line(1, "java.lang.IllegalArgumentException: arg error"),
                continuationLine(2, "\tat com.a.A.m(A.java:1)"),
                line(3, "java.lang.ClassCastException: cast error"),
                continuationLine(4, "\tat com.b.B.m(B.java:2)"),
                line(5, "java.sql.SQLException: db error"),
                continuationLine(6, "\tat com.c.C.m(C.java:3)")
        );

        var results = analyzer.analyze(lines);

        assertEquals(3, results.size());
        var types = results.stream().map(ExceptionLocationSummary::exceptionType).toList();
        assertTrue(types.contains("IllegalArgumentException"));
        assertTrue(types.contains("ClassCastException"));
        assertTrue(types.contains("SQLException"));
    }
}
