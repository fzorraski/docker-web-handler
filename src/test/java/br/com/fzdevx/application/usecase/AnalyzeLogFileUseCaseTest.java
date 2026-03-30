package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.LogAnalysisPort;
import br.com.fzdevx.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyzeLogFileUseCaseTest {

    @Mock LogAnalysisPort logAnalysisPort;

    @InjectMocks
    AnalyzeLogFileUseCase useCase;

    @BeforeEach
    void setUp() {
        setField("maxFiles", 5);
        setField("ttlMinutes", 120);
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
                        100, "req", "resp", 1, 2, "test.log", false)),
                List.of(new EndpointStats("OrderWS/getOrders", 1, 100.0, 100, 100, 100, 0)),
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
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(expected);

        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000);

        assertNotNull(result);
        assertEquals(100, result.getTotalLineCount());
        verify(logAnalysisPort).analyze(any(), eq(List.of("test.log")), eq(LogPreset.WILDFLY), eq(1000));
    }

    @Test
    void analyze_storesResultForRetrieval() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());

        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000);

        assertNotNull(useCase.get(result.getId()));
        assertEquals(result.getId(), useCase.get(result.getId()).getId());
    }

    @Test
    void analyze_appearsInListAll() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());

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
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());
        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000);

        assertTrue(useCase.delete(result.getId()));
        assertNull(useCase.get(result.getId()));
    }

    @Test
    void delete_nonExistentId_returnsFalse() {
        assertFalse(useCase.delete("non-existent"));
    }

    @Test
    void delete_removesFromList() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());
        LogAnalysis result = useCase.analyze(List.of(Path.of("/tmp/test.log")), List.of("test.log"), LogPreset.WILDFLY, 1000);

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

        when(logAnalysisPort.analyze(any(), any(), any(), anyInt()))
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
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt()))
                .thenReturn(makeAnalysis()).thenReturn(makeAnalysis());

        LogAnalysis first = useCase.analyze(List.of(Path.of("/tmp/1.log")), List.of("1.log"), LogPreset.WILDFLY, 1000);
        LogAnalysis second = useCase.analyze(List.of(Path.of("/tmp/2.log")), List.of("2.log"), LogPreset.WILDFLY, 1000);

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
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());
        LogAnalysis a1 = useCase.analyze(List.of(Path.of("/tmp/1.log")), List.of("1.log"), LogPreset.WILDFLY, 1000);

        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());
        LogAnalysis a2 = useCase.analyze(List.of(Path.of("/tmp/2.log")), List.of("2.log"), LogPreset.WILDFLY, 1000);

        LogAnalysis composed = useCase.compose(List.of(a1.getId(), a2.getId()), LogPreset.WILDFLY, 1000);

        assertNotNull(composed);
        assertNotEquals(a1.getId(), composed.getId());
        assertNotEquals(a2.getId(), composed.getId());
        assertNotNull(useCase.get(composed.getId()));
    }

    @Test
    void compose_addedToListAll() {
        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());
        LogAnalysis a1 = useCase.analyze(List.of(Path.of("/tmp/1.log")), List.of("1.log"), LogPreset.WILDFLY, 1000);

        when(logAnalysisPort.analyze(any(), any(), any(), anyInt())).thenReturn(makeAnalysis());
        LogAnalysis a2 = useCase.analyze(List.of(Path.of("/tmp/2.log")), List.of("2.log"), LogPreset.WILDFLY, 1000);

        useCase.compose(List.of(a1.getId(), a2.getId()), LogPreset.WILDFLY, 1000);

        assertEquals(3, useCase.listAll().size());
    }
}
