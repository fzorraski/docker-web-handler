package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.LogLine;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryLogLinesUseCaseTest {

    private final QueryLogLinesUseCase useCase = new QueryLogLinesUseCase();

    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 15, 10, 0, 0);

    private List<LogLine> sampleLines() {
        return List.of(
                new LogLine(1, NOW, "INFO", "com.example.App", "main", "Application started", "server.log"),
                new LogLine(2, NOW.plusSeconds(1), "WARN", "com.example.Db", "db-pool-1", "Slow query detected", "server.log"),
                new LogLine(3, NOW.plusSeconds(2), "ERROR", "com.example.Api", "http-thread-1", "NullPointerException in UserService", "server.log"),
                new LogLine(4, NOW.plusSeconds(3), "INFO", "com.example.Api", "http-thread-1", "Request processed", "server.log"),
                new LogLine(5, NOW.plusSeconds(4), "DEBUG", "com.example.App", "main", null, "server.log")
        );
    }

    // ======================================================================
    // Exclude — basic behavior
    // ======================================================================

    @Test
    void exclude_null_returnsAllLines() {
        var result = useCase.queryLines(sampleLines(), null, null, null, null, 0, 100);
        assertEquals(5, result.total());
    }

    @Test
    void exclude_blank_returnsAllLines() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "  ", 0, 100);
        assertEquals(5, result.total());
    }

    @Test
    void exclude_empty_returnsAllLines() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "", 0, 100);
        assertEquals(5, result.total());
    }

    @Test
    void exclude_singlePattern_excludesMatchingLines() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "Slow query", 0, 100);
        assertEquals(4, result.total());
        assertTrue(result.data().stream().noneMatch(l -> l.message() != null && l.message().contains("Slow query")));
    }

    @Test
    void exclude_multiplePatterns_excludesAll() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "Slow query,started", 0, 100);
        assertEquals(3, result.total());
        assertTrue(result.data().stream().noneMatch(l ->
                l.message() != null && (l.message().toLowerCase().contains("slow query")
                        || l.message().toLowerCase().contains("started"))));
    }

    // ======================================================================
    // Exclude — case insensitivity
    // ======================================================================

    @Test
    void exclude_caseInsensitive_uppercasePattern() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "SLOW QUERY", 0, 100);
        assertEquals(4, result.total());
        assertTrue(result.data().stream().noneMatch(l -> l.message() != null && l.message().contains("Slow query")));
    }

    @Test
    void exclude_caseInsensitive_mixedCasePattern() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "sLoW qUeRy", 0, 100);
        assertEquals(4, result.total());
    }

    // ======================================================================
    // Exclude — null message handling
    // ======================================================================

    @Test
    void exclude_nullMessage_lineIsNotExcluded() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "anything", 0, 100);
        assertTrue(result.data().stream().anyMatch(l -> l.message() == null),
                "Lines with null messages should pass through exclude filter");
    }

    @Test
    void exclude_nullMessage_withSearch_lineIsExcluded() {
        var result = useCase.queryLines(sampleLines(), null, null, "something", null, 0, 100);
        assertTrue(result.data().stream().noneMatch(l -> l.message() == null),
                "Lines with null messages should be excluded when search is active");
    }

    // ======================================================================
    // Exclude — whitespace and edge cases in pattern input
    // ======================================================================

    @Test
    void exclude_patternsWithExtraWhitespace_trimmed() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "  Slow query  ,  started  ", 0, 100);
        assertEquals(3, result.total());
    }

    @Test
    void exclude_trailingComma_ignored() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "Slow query,", 0, 100);
        assertEquals(4, result.total());
    }

    @Test
    void exclude_onlyCommas_returnsAllLines() {
        var result = useCase.queryLines(sampleLines(), null, null, null, ",,,", 0, 100);
        assertEquals(5, result.total());
    }

    @Test
    void exclude_commasWithSpaces_returnsAllLines() {
        var result = useCase.queryLines(sampleLines(), null, null, null, " , , , ", 0, 100);
        assertEquals(5, result.total());
    }

    // ======================================================================
    // Exclude — combined with search (AND logic)
    // ======================================================================

    @Test
    void exclude_combinedWithSearch_bothFiltersApply() {
        // Search for lines containing "e" (all except null), then exclude "Slow query"
        var result = useCase.queryLines(sampleLines(), null, null, "e", "Slow query", 0, 100);
        // Lines with "e": "Application started" (line 1 — "started"), "Slow query detected" (line 2),
        // "NullPointerException in UserService" (line 3), "Request processed" (line 4)
        // Excluding "Slow query": removes line 2
        assertEquals(3, result.total());
        assertTrue(result.data().stream().allMatch(l -> l.message().toLowerCase().contains("e")));
        assertTrue(result.data().stream().noneMatch(l -> l.message().toLowerCase().contains("slow query")));
    }

    @Test
    void exclude_combinedWithSearch_noOverlap_emptyResult() {
        // Search for "Slow query" but also exclude it
        var result = useCase.queryLines(sampleLines(), null, null, "Slow query", "Slow query", 0, 100);
        assertEquals(0, result.total());
    }

    // ======================================================================
    // Exclude — combined with thread and level
    // ======================================================================

    @Test
    void exclude_combinedWithThread_bothApply() {
        var result = useCase.queryLines(sampleLines(), "http-thread-1", null, null, "NullPointer", 0, 100);
        assertEquals(1, result.total());
        assertEquals("Request processed", result.data().getFirst().message());
    }

    @Test
    void exclude_combinedWithLevel_bothApply() {
        var result = useCase.queryLines(sampleLines(), null, "INFO", null, "started", 0, 100);
        assertEquals(1, result.total());
        assertEquals("Request processed", result.data().getFirst().message());
    }

    // ======================================================================
    // Exclude — all lines excluded
    // ======================================================================

    @Test
    void exclude_allLinesExcluded_emptyResult() {
        var result = useCase.queryLines(sampleLines(), null, null, null,
                "Application,Slow query,NullPointer,Request processed", 0, 100);
        // Only the null-message line (5) survives
        assertEquals(1, result.total());
        assertNull(result.data().getFirst().message());
    }

    // ======================================================================
    // Exclude — pagination
    // ======================================================================

    @Test
    void exclude_withPagination_totalReflectsFilteredCount() {
        var result = useCase.queryLines(sampleLines(), null, null, null, "Slow query,started", 0, 2);
        assertEquals(3, result.total());
        assertEquals(2, result.data().size());

        var page2 = useCase.queryLines(sampleLines(), null, null, null, "Slow query,started", 1, 2);
        assertEquals(3, page2.total());
        assertEquals(1, page2.data().size());
    }

    // ======================================================================
    // Exclude — partial substring match
    // ======================================================================

    @Test
    void exclude_partialSubstring_matchesAnywhere() {
        // "Null" should match "NullPointerException in UserService"
        var result = useCase.queryLines(sampleLines(), null, null, null, "Null", 0, 100);
        assertEquals(4, result.total());
        assertTrue(result.data().stream().noneMatch(l -> l.message() != null && l.message().contains("Null")));
    }

    @Test
    void exclude_partialSubstring_middleOfWord() {
        // "Pointer" should match "NullPointerException in UserService"
        var result = useCase.queryLines(sampleLines(), null, null, null, "Pointer", 0, 100);
        assertEquals(4, result.total());
    }
}
