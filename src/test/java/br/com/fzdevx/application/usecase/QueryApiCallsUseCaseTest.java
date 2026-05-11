package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ApiCallQuery;
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
                durationMs, -1, false, "{}", "{}", 1, 2, "test.log", slow);
    }

    private ApiCallQuery query(String endpoint, Long minDuration, Long maxDuration, boolean slowOnly,
                               String search, String exclude, String sort, String sortDir, int page, int size) {
        return new ApiCallQuery(endpoint, null, minDuration, maxDuration, slowOnly, false,
                null, null, search, exclude, null, null, sort, sortDir, page, size);
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
        var result = useCase.execute(testData, query("vehicle", null, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(3, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.endpoint().contains("vehicle")));
    }

    @Test
    void endpointFilter_caseInsensitive() {
        var result = useCase.execute(testData, query("VEHICLE", null, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(3, result.total());
    }

    @Test
    void endpointFilter_exactMatch() {
        var result = useCase.execute(testData, query("/api/health", null, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(1, result.total());
        assertEquals("/api/health", result.data().getFirst().endpoint());
    }

    @Test
    void endpointFilter_noMatch() {
        var result = useCase.execute(testData, query("orders", null, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(0, result.total());
    }

    @Test
    void endpointFilter_nullOrBlank_returnsAll() {
        var result1 = useCase.execute(testData, query(null, null, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(6, result1.total());

        var result2 = useCase.execute(testData, query("", null, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(6, result2.total());
    }

    // ---- Slow only filter ----

    @Test
    void slowOnly_filtersSlowCalls() {
        var result = useCase.execute(testData, query(null, null, null, true, null, null, "time", "asc", 0, 50));
        assertEquals(2, result.total());
        assertTrue(result.data().stream().allMatch(ApiCallPair::slow));
    }

    @Test
    void slowOnly_false_returnsAll() {
        var result = useCase.execute(testData, query(null, null, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(6, result.total());
    }

    // ---- Duration range filter ----

    @Test
    void minDuration_filtersBelow() {
        var result = useCase.execute(testData, query(null, 1000L, null, false, null, null, "time", "asc", 0, 50));
        assertEquals(2, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.durationMs() >= 1000));
    }

    @Test
    void maxDuration_filtersAbove() {
        var result = useCase.execute(testData, query(null, null, 500L, false, null, null, "time", "asc", 0, 50));
        assertEquals(4, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.durationMs() <= 500));
    }

    @Test
    void durationRange_minAndMax() {
        var result = useCase.execute(testData, query(null, 100L, 1500L, false, null, null, "time", "asc", 0, 50));
        assertEquals(3, result.total());
        assertTrue(result.data().stream().allMatch(c -> c.durationMs() >= 100 && c.durationMs() <= 1500));
    }

    // ---- Combined filters ----

    @Test
    void combinedFilters_endpointAndSlowOnly() {
        var result = useCase.execute(testData, query("vehicle", null, null, true, null, null, "time", "asc", 0, 50));
        assertEquals(1, result.total());
        assertEquals("/api/vehicle/color", result.data().getFirst().endpoint());
    }

    @Test
    void combinedFilters_endpointAndDurationRange() {
        var result = useCase.execute(testData, query("vehicle", 100L, 2000L, false, null, null, "time", "asc", 0, 50));
        assertEquals(2, result.total());
    }

    @Test
    void combinedFilters_allFilters() {
        var result = useCase.execute(testData, query("api", 50L, 3000L, false, null, null, "time", "asc", 0, 50));
        assertEquals(5, result.total());
    }

    // ---- Pagination ----

    @Test
    void pagination_withFilters() {
        var result = useCase.execute(testData, query("vehicle", null, null, false, null, null, "time", "asc", 0, 2));
        assertEquals(3, result.total());
        assertEquals(2, result.data().size());
    }

    @Test
    void pagination_secondPage() {
        var result = useCase.execute(testData, query("vehicle", null, null, false, null, null, "time", "asc", 1, 2));
        assertEquals(3, result.total());
        assertEquals(1, result.data().size());
    }

    // ---- Sorting ----

    @Test
    void sortByDuration_desc() {
        var result = useCase.execute(testData, query(null, null, null, false, null, null, "duration", "desc", 0, 50));
        assertEquals(3000, result.data().getFirst().durationMs());
    }

    @Test
    void sortByEndpoint_asc() {
        var result = useCase.execute(testData, query(null, null, null, false, null, null, "endpoint", "asc", 0, 50));
        assertEquals("/api/health", result.data().getFirst().endpoint());
    }

    // ---- Connection delay filters and sorting ----

    private ApiCallPair callWithUpstream(String endpoint, long durationMs, long upstreamMs, boolean slowConn) {
        return new ApiCallPair(endpoint, null, "http-1",
                LocalDateTime.of(2026, 4, 20, 10, 0), LocalDateTime.of(2026, 4, 20, 10, 1),
                durationMs, upstreamMs, slowConn, "{}", "{}", 1, 2, "test.log", false);
    }

    private final List<ApiCallPair> connectionData = List.of(
            callWithUpstream("/api/fast", 100, 80, false),       // delay=20, not slow
            callWithUpstream("/api/slow-conn", 200, 50, true),   // delay=150, slow
            callWithUpstream("/api/no-upstream", 300, -1, false), // delay=-1, no upstream
            callWithUpstream("/api/medium", 500, 400, true)      // delay=100, slow
    );

    @Test
    void slowConnectionOnly_filtersSlowConnections() {
        var q = new ApiCallQuery(null, null, null, null, false, true,
                null, null, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        assertEquals(2, result.total());
        assertTrue(result.data().stream().allMatch(ApiCallPair::slowConnection));
    }

    @Test
    void slowConnectionOnly_false_returnsAll() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                null, null, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        assertEquals(4, result.total());
    }

    @Test
    void minConnectionDelay_filtersBelow() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                100L, null, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        assertEquals(2, result.total()); // delay=150 and delay=100
        assertTrue(result.data().stream().allMatch(c -> c.connectionDelayMs() >= 100));
    }

    @Test
    void minConnectionDelay_excludesNoUpstream() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                0L, null, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        // Should include delay=20, delay=150, delay=100 but NOT delay=-1
        assertEquals(3, result.total());
        assertTrue(result.data().stream().noneMatch(c -> c.connectionDelayMs() < 0));
    }

    @Test
    void maxConnectionDelay_filtersAbove() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                null, 50L, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        assertEquals(1, result.total()); // only delay=20
        assertEquals("/api/fast", result.data().getFirst().endpoint());
    }

    @Test
    void maxConnectionDelay_excludesNoUpstream() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                null, 1000L, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        // All with known delay pass (20, 150, 100) but -1 excluded
        assertEquals(3, result.total());
    }

    @Test
    void connectionDelayRange_minAndMax() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                50L, 120L, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        assertEquals(1, result.total()); // only delay=100
        assertEquals("/api/medium", result.data().getFirst().endpoint());
    }

    @Test
    void sortByUpstream_asc() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                null, null, null, null, null, null, "upstream", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        // -1, 50, 80, 400
        assertEquals(-1, result.data().get(0).upstreamDurationMs());
        assertEquals(400, result.data().get(3).upstreamDurationMs());
    }

    @Test
    void sortByUpstream_desc() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                null, null, null, null, null, null, "upstream", "desc", 0, 50);
        var result = useCase.execute(connectionData, q);
        assertEquals(400, result.data().get(0).upstreamDurationMs());
    }

    @Test
    void sortByConnectionDelay_asc() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                null, null, null, null, null, null, "connectionDelay", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        // -1, 20, 100, 150
        assertEquals(-1, result.data().get(0).connectionDelayMs());
        assertEquals(150, result.data().get(3).connectionDelayMs());
    }

    @Test
    void sortByConnectionDelay_desc() {
        var q = new ApiCallQuery(null, null, null, null, false, false,
                null, null, null, null, null, null, "connectionDelay", "desc", 0, 50);
        var result = useCase.execute(connectionData, q);
        assertEquals(150, result.data().get(0).connectionDelayMs());
    }

    @Test
    void combinedFilters_slowConnectionAndMinDelay() {
        var q = new ApiCallQuery(null, null, null, null, false, true,
                120L, null, null, null, null, null, "time", "asc", 0, 50);
        var result = useCase.execute(connectionData, q);
        // slowConn=true AND delay>=120: only delay=150 (/api/slow-conn)
        assertEquals(1, result.total());
        assertEquals("/api/slow-conn", result.data().getFirst().endpoint());
    }
}
