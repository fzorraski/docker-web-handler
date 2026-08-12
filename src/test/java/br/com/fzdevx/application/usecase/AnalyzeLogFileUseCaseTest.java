package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.port.CustomFieldExtractorPort;
import br.com.fzdevx.application.port.LogAnalysisPort;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.infrastructure.log.CriticalIssueDetector;
import br.com.fzdevx.infrastructure.log.ExceptionAnalyzer;
import br.com.fzdevx.infrastructure.log.NpeAnalyzer;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyzeLogFileUseCaseTest {

    @Mock LogAnalysisPort logAnalysisPort;
    @Mock CustomFieldExtractorPort customFieldExtractorPort;
    @Mock CriticalIssueDetector criticalIssueDetector;
    @Mock NpeAnalyzer npeAnalyzer;
    @Mock ExceptionAnalyzer exceptionAnalyzer;
    @Mock ResourceCounterService resourceCounterService;

    @InjectMocks
    AnalyzeLogFileUseCase useCase;

    @BeforeEach
    void setUp() {
        setField("maxFiles", 5);
        setField("ttlMinutes", 120);
        setField("analysisExecutor", java.util.concurrent.Executors.newFixedThreadPool(4));
    }

    private void setField(String name, Object value) {
        try {
            Field f = AnalyzeLogFileUseCase.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(useCase, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void stubProgressAnalyze(LogAnalysis result) {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    // Invoke the onLinesParsed callback with the analysis's lines
                    Consumer<List<LogLine>> callback = inv.getArgument(7);
                    if (callback != null) {
                        callback.accept(result.getAllLines());
                    }
                    return result;
                });
    }

    private LogAnalysis makeAnalysis() {
        return new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("test.log", 1024)),
                100,
                LocalDateTime.of(2026, 3, 30, 0, 0),
                LocalDateTime.of(2026, 3, 30, 23, 59),
                List.of("thread-1"),
                List.of("OrderWS/getOrders"),
                List.of(new ApiCallPair("OrderWS/getOrders", null, "thread-1",
                        LocalDateTime.of(2026, 3, 30, 7, 31, 0),
                        LocalDateTime.of(2026, 3, 30, 7, 31, 0, 100_000_000),
                        100, -1, false, "req", "resp", 1, 2, "test.log", false)),
                List.of(new EndpointStats("OrderWS/getOrders", 1, 100.0, 100, 100, 100, 0, -1, -1, 0)),
                Map.of("INFO", 95, "ERROR", 5),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    // ---- analyze ----

    @Test
    void analyze_delegatesToPort() {
        LogAnalysis expected = makeAnalysis();
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(expected);

        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000).analysis();

        assertNotNull(result);
        assertEquals(100, result.getTotalLineCount());
        verify(logAnalysisPort).analyze(any(), eq(List.of("test.log")), eq(LogPreset.WILDFLY), eq(1000), any());
    }

    @Test
    void analyze_storesResultForRetrieval() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());

        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000).analysis();

        assertNotNull(useCase.get(result.getId()));
        assertEquals(result.getId(), useCase.get(result.getId()).getId());
    }

    @Test
    void analyze_appearsInListAll() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());

        useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000);

        assertEquals(1, useCase.listAll().size());
    }

    // ---- get ----

    @Test
    void get_nonExistentId_returnsNull() {
        assertNull(useCase.get("non-existent-id"));
    }

    // ---- delete ----

    @Test
    void delete_existingAnalysis_returnsTrue() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());
        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000).analysis();

        assertTrue(useCase.delete(result.getId()));
        assertNull(useCase.get(result.getId()));
    }

    @Test
    void delete_nonExistentId_returnsFalse() {
        assertFalse(useCase.delete("non-existent"));
    }

    @Test
    void delete_removesFromList() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());
        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000).analysis();

        useCase.delete(result.getId());

        assertTrue(useCase.listAll().isEmpty());
    }

    // ---- max files eviction ----

    @Test
    void analyze_evictsOldestWhenMaxReached() {
        setField("maxFiles", 2);

        LogAnalysis a1 = makeAnalysis();
        LogAnalysis a2 = makeAnalysis();
        LogAnalysis a3 = makeAnalysis();

        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any()))
                .thenReturn(a1).thenReturn(a2).thenReturn(a3);

        useCase.analyze(List.of(Path.of("/tmp/1.log")), List.of("1.log"), LogPreset.WILDFLY, 1000);
        useCase.analyze(List.of(Path.of("/tmp/2.log")), List.of("2.log"), LogPreset.WILDFLY, 1000);
        useCase.analyze(List.of(Path.of("/tmp/3.log")), List.of("3.log"), LogPreset.WILDFLY, 1000);

        assertEquals(2, useCase.listAll().size());
        assertNull(useCase.get(a1.getId()));
    }

    // ---- listAll ordering ----

    @Test
    void listAll_orderedByUploadTimeDescending() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any()))
                .thenReturn(makeAnalysis()).thenReturn(makeAnalysis());

        LogAnalysis first = useCase.analyze(List.of(Path.of("/tmp/1.log")), List.of("1.log"), LogPreset.WILDFLY, 1000).analysis();
        LogAnalysis second = useCase.analyze(List.of(Path.of("/tmp/2.log")), List.of("2.log"), LogPreset.WILDFLY, 1000).analysis();

        List<LogAnalysis> list = useCase.listAll();
        assertEquals(2, list.size());
        assertEquals(second.getId(), list.get(0).getId());
        assertEquals(first.getId(), list.get(1).getId());
    }

    // ---- compose ----

    @Test
    void compose_withInvalidIds_returnsNull() {
        assertNull(useCase.compose(List.of("bad1", "bad2"), LogPreset.WILDFLY, 1000));
    }

    @Test
    void compose_mergesTwoAnalyses() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());
        LogAnalysis a1 = useCase.analyze(List.of(Path.of("/tmp/1.log")), List.of("1.log"), LogPreset.WILDFLY, 1000).analysis();

        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());
        LogAnalysis a2 = useCase.analyze(List.of(Path.of("/tmp/2.log")), List.of("2.log"), LogPreset.WILDFLY, 1000).analysis();

        LogAnalysis composed = useCase.compose(List.of(a1.getId(), a2.getId()), LogPreset.WILDFLY, 1000);

        assertNotNull(composed);
        assertNotEquals(a1.getId(), composed.getId());
        assertNotEquals(a2.getId(), composed.getId());
        assertNotNull(useCase.get(composed.getId()));
    }

    @Test
    void compose_addedToListAll() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());
        LogAnalysis a1 = useCase.analyze(List.of(Path.of("/tmp/1.log")), List.of("1.log"), LogPreset.WILDFLY, 1000).analysis();

        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any())).thenReturn(makeAnalysis());
        LogAnalysis a2 = useCase.analyze(List.of(Path.of("/tmp/2.log")), List.of("2.log"), LogPreset.WILDFLY, 1000).analysis();

        useCase.compose(List.of(a1.getId(), a2.getId()), LogPreset.WILDFLY, 1000);

        assertEquals(3, useCase.listAll().size());
    }

    // ---- attribution ----

    @Test
    void attribution_trimsAndTruncatesLabel() {
        assertEquals("my-label", new AnalyzeLogFileUseCase.Attribution(null, "  my-label  ").label());
        assertNull(new AnalyzeLogFileUseCase.Attribution(null, "   ").label());
        assertNull(new AnalyzeLogFileUseCase.Attribution(null, null).label());
        assertEquals(50, new AnalyzeLogFileUseCase.Attribution(null, "x".repeat(80)).label().length());
    }

    @Test
    void analyzeWithProgress_stampsAttributionBeforeSuccessEvent() {
        LogAnalysis expected = makeAnalysis();
        stubProgressAnalyze(expected);

        // the client refreshes its list on SUCCESS, so uploader and label must already be
        // visible on the published analysis by the time the event is emitted
        List<String[]> stampedAtSuccess = new ArrayList<>();
        useCase.analyzeWithProgress(
                List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all(),
                event -> {
                    if (event.getType() == ContainerEvent.EventType.SUCCESS) {
                        LogAnalysis published = useCase.get(event.getDetail());
                        stampedAtSuccess.add(new String[]{published.getUploadedBy(), published.getLabel()});
                    }
                },
                "ticket-attr", evicted -> {},
                new AnalyzeLogFileUseCase.Attribution("alice", "  nightly  "));

        assertEquals(1, stampedAtSuccess.size());
        assertEquals("alice", stampedAtSuccess.getFirst()[0]);
        assertEquals("nightly", stampedAtSuccess.getFirst()[1]);
    }

    @Test
    void analyze_stampsAttributionOnResult() {
        LogAnalysis expected = makeAnalysis();
        when(logAnalysisPort.analyze(anyList(), anyList(), any(), anyInt(), any())).thenReturn(expected);

        var result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all(),
                new AnalyzeLogFileUseCase.Attribution("bob", null));

        assertEquals("bob", result.analysis().getUploadedBy());
        assertEquals("bob", useCase.get(expected.getId()).getUploadedBy());
    }

    // ---- analyzeWithProgress ----

    @Test
    void analyzeWithProgress_sendsEventsAndStoresResult() {
        LogAnalysis expected = makeAnalysis();
        stubProgressAnalyze(expected);

        List<ContainerEvent> events = new ArrayList<>();
        useCase.analyzeWithProgress(
                List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all(),
                events::add, "ticket-1", evicted -> {});

        // Should have at least a start INFO and a final SUCCESS
        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.INFO));
        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.SUCCESS));

        // SUCCESS event should carry the analysis ID
        ContainerEvent success = events.stream()
                .filter(e -> e.getType() == ContainerEvent.EventType.SUCCESS).findFirst().orElseThrow();
        assertEquals(expected.getId(), success.getDetail());

        // Analysis should be stored
        assertNotNull(useCase.get(expected.getId()));
    }

    @Test
    void analyzeWithProgress_cancellation_sendsErrorEvent() {
        // Simulate cancellation during parsing: the port throws CancellationException
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenThrow(new CancellationException("cancelled"));

        List<ContainerEvent> events = new ArrayList<>();
        useCase.analyzeWithProgress(
                List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all(),
                events::add, "ticket-cancel", evicted -> {});

        assertTrue(events.stream()
                .anyMatch(e -> e.getType() == ContainerEvent.EventType.ERROR
                        && e.getMessage().toLowerCase().contains("cancel")));
    }

    @Test
    void analyzeWithProgress_exception_sendsErrorEvent() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("disk full"));

        List<ContainerEvent> events = new ArrayList<>();
        useCase.analyzeWithProgress(
                List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all(),
                events::add, "ticket-err", evicted -> {});

        assertTrue(events.stream()
                .anyMatch(e -> e.getType() == ContainerEvent.EventType.ERROR
                        && e.getMessage().contains("disk full")));
    }

    @Test
    void analyzeWithProgress_sendsCustomFieldEvents() {
        LogAnalysis expected = makeAnalysis();
        stubProgressAnalyze(expected);
        when(customFieldExtractorPort.extract(any(), any(), any())).thenReturn(List.of());

        LogPreset presetWithFields = new LogPreset("WildFly",
                LogPreset.WILDFLY.logLineRegex(), LogPreset.WILDFLY.timestampFormat(),
                LogPreset.WILDFLY.apiCallRegex(), null, null, null, List.of(),
                List.of(new LogPreset.CustomField("Test", ".*", false)), List.of(), null);

        List<ContainerEvent> events = new ArrayList<>();
        useCase.analyzeWithProgress(
                List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                presetWithFields, 1000, AnalysisOptions.all(),
                events::add, "ticket-cf", evicted -> {});

        assertTrue(events.stream()
                .anyMatch(e -> "Custom Fields".equals(e.getStep())));
    }

    // ---- cancel ----

    @Test
    void cancel_unknownTicket_returnsFalse() {
        assertFalse(useCase.cancel("nonexistent"));
    }

    @Test
    void cancel_activeTicket_returnsTrue() {
        // Start an analysis that blocks so we can cancel it
        AtomicBoolean parserStarted = new AtomicBoolean(false);
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    parserStarted.set(true);
                    AtomicBoolean cancelled = inv.getArgument(6);
                    // Wait until cancelled
                    while (!cancelled.get()) {
                        Thread.sleep(10);
                    }
                    throw new CancellationException("cancelled");
                });

        Thread analysisThread = new Thread(() ->
                useCase.analyzeWithProgress(
                        List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                        LogPreset.WILDFLY, 1000, AnalysisOptions.all(),
                        e -> {}, "ticket-active", evicted -> {}));
        analysisThread.start();

        // Wait for analysis to start
        while (!parserStarted.get()) {
            Thread.onSpinWait();
        }

        assertTrue(useCase.cancel("ticket-active"));

        try { analysisThread.join(5000); } catch (InterruptedException ignored) {}
        assertFalse(analysisThread.isAlive());
    }

    @Test
    void cancel_afterCompletion_returnsFalse() {
        stubProgressAnalyze(makeAnalysis());

        useCase.analyzeWithProgress(
                List.of(Path.of("/tmp/test.log")), List.of("test.log"),
                LogPreset.WILDFLY, 1000, AnalysisOptions.all(),
                e -> {}, "ticket-done", evicted -> {});

        // Ticket removed from activeRuns after completion
        assertFalse(useCase.cancel("ticket-done"));
    }
}
