package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.ApiCallPair;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryApiCallsUseCaseTest {

    private final QueryApiCallsUseCase useCase = new QueryApiCallsUseCase();

    private ApiCallPair call(String endpoint, long durationMs, boolean slow) {
        return new ApiCallPair(endpoint, null, "http-1",
                LocalDateTime.of(2026, 4, 20, 10, 0), LocalDateTime.of(2026, 4, 20, 10, 1),
                durationMs, "{}", "{}", 1, 2, "test.log", slow);
    }

    private final List<ApiCallPair> testData = List.of(
            call("/api/vehicle/price", 200, false),
            call("/api/vehicle/color", 1500, true),
            call("/api/vehicle/weight", 50, false),
            call("/api/users/list", 3000, true),
            call("/api/users/detail", 100, false),
            call("/api/health", 10, false)
    );

    // ---- Endpoint substring filter ----

    @Test
    void endpointFilter_substringMatch() {
        var result = useCase.execute(testData,
                "vehicle", null, null, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(3, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.endpoint().contains("vehicle")));
    }

    @Test
    void endpointFilter_caseInsensitive() {
        var result = useCase.execute(testData,
                "VEHICLE", null, null, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(3, result.total());
    }

    @Test
    void endpointFilter_exactMatch() {
        var result = useCase.execute(testData,
                "/api/health", null, null, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(1, result.total());
        assertEquals("/api/health", result.data().getFirst().endpoint());
    }

    @Test
    void endpointFilter_noMatch() {
        var result = useCase.execute(testData,
                "orders", null, null, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(0, result.total());
    }

    @Test
    void endpointFilter_nullOrBlank_returnsAll() {
        var result1 = useCase.execute(testData,
                null, null, null, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(6, result1.total());

        var result2 = useCase.execute(testData,
                "", null, null, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(6, result2.total());
    }

    // ---- Slow only filter ----

    @Test
    void slowOnly_filtersSlowCalls() {
        var result = useCase.execute(testData,
                null, null, null, null, true, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(2, result.total());
        assertTrue(result.data().stream().allMatch(ApiCallPair::slow));
    }

    @Test
    void slowOnly_false_returnsAll() {
        var result = useCase.execute(testData,
                null, null, null, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(6, result.total());
    }

    // ---- Duration range filter ----

    @Test
    void minDuration_filtersBelow() {
        var result = useCase.execute(testData,
                null, null, 1000L, null, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(2, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.durationMs() >= 1000));
    }

    @Test
    void maxDuration_filtersAbove() {
        var result = useCase.execute(testData,
                null, null, null, 500L, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(4, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.durationMs() <= 500));
    }

    @Test
    void durationRange_minAndMax() {
        var result = useCase.execute(testData,
                null, null, 100L, 1500L, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(3, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.durationMs() >= 100 && c.durationMs() <= 1500));
    }

    // ---- Combined filters ----

    @Test
    void combinedFilters_endpointAndSlowOnly() {
        var result = useCase.execute(testData,
                "vehicle", null, null, null, true, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(1, result.total());
        assertEquals("/api/vehicle/color", result.data().getFirst().endpoint());
    }

    @Test
    void combinedFilters_endpointAndDurationRange() {
        var result = useCase.execute(testData,
                "vehicle", null, 100L, 2000L, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(2, result.total());
    }

    @Test
    void combinedFilters_allFilters() {
        var result = useCase.execute(testData,
                "api", null, 50L, 3000L, false, null, null, null, null, "time", "asc", 0, 50);
        assertEquals(5, result.total());
    }

    // ---- Pagination ----

    @Test
    void pagination_withFilters() {
        var result = useCase.execute(testData,
                "vehicle", null, null, null, false, null, null, null, null, "time", "asc", 0, 2);
        assertEquals(3, result.total());
        assertEquals(2, result.data().size());
    }

    @Test
    void pagination_secondPage() {
        var result = useCase.execute(testData,
                "vehicle", null, null, null, false, null, null, null, null, "time", "asc", 1, 2);
        assertEquals(3, result.total());
        assertEquals(1, result.data().size());
    }

    // ---- Sorting ----

    @Test
    void sortByDuration_desc() {
        var result = useCase.execute(testData,
                null, null, null, null, false, null, null, null, null, "duration", "desc", 0, 50);
        assertEquals(3000, result.data().getFirst().durationMs());
    }

    @Test
    void sortByEndpoint_asc() {
        var result = useCase.execute(testData,
                null, null, null, null, false, null, null, null, null, "endpoint", "asc", 0, 50);
        assertEquals("/api/health", result.data().getFirst().endpoint());
    }
}
