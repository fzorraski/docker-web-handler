package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.EndpointStats;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndpointStatsCalculatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 1, 10, 0, 0);

    private ApiCallPair call(String endpoint, long durationMs, long upstreamMs, boolean slowConn) {
        return new ApiCallPair(endpoint, null, "t-1", NOW, NOW.plusSeconds(1),
                durationMs, upstreamMs, slowConn, null, null, 1, 2, "test.log",
                durationMs >= 1000);
    }

    // ---- Basic duration stats ----

    @Test
    void compute_singleEndpoint_basicStats() {
        var calls = List.of(
                call("/api/a", 100, -1, false),
                call("/api/a", 300, -1, false),
                call("/api/a", 500, -1, false)
        );
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        assertEquals(1, stats.size());
        EndpointStats s = stats.getFirst();
        assertEquals("/api/a", s.endpoint());
        assertEquals(3, s.callCount());
        assertEquals(300.0, s.avgDurationMs(), 0.01);
        assertEquals(100, s.minDurationMs());
        assertEquals(500, s.maxDurationMs());
        assertEquals(0, s.slowCount());
    }

    @Test
    void compute_multipleEndpoints_sortedByCallCountDesc() {
        var calls = List.of(
                call("/api/a", 100, -1, false),
                call("/api/b", 200, -1, false),
                call("/api/b", 300, -1, false),
                call("/api/b", 400, -1, false)
        );
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        assertEquals(2, stats.size());
        assertEquals("/api/b", stats.get(0).endpoint());
        assertEquals("/api/a", stats.get(1).endpoint());
    }

    @Test
    void compute_slowCount_basedOnThreshold() {
        var calls = List.of(
                call("/api/a", 500, -1, false),
                call("/api/a", 1000, -1, false),
                call("/api/a", 2000, -1, false)
        );
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        assertEquals(2, stats.getFirst().slowCount());
    }

    // ---- Connection delay stats — no upstream data ----

    @Test
    void compute_noUpstreamData_connectionDelayFieldsNegative() {
        var calls = List.of(
                call("/api/a", 100, -1, false),
                call("/api/a", 200, -1, false)
        );
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        EndpointStats s = stats.getFirst();
        assertEquals(-1, s.avgConnectionDelayMs());
        assertEquals(-1, s.p95ConnectionDelayMs());
        assertEquals(0, s.slowConnectionCount());
    }

    // ---- Connection delay stats — with upstream data ----

    @Test
    void compute_withUpstreamData_avgConnectionDelay() {
        var calls = List.of(
                call("/api/a", 100, 50, false),   // delay = 50
                call("/api/a", 200, 100, false),   // delay = 100
                call("/api/a", 300, 150, false)    // delay = 150
        );
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        EndpointStats s = stats.getFirst();
        assertEquals(100.0, s.avgConnectionDelayMs(), 0.01);
    }

    @Test
    void compute_withUpstreamData_p95ConnectionDelay() {
        // 20 calls with delays 1..20
        var calls = new java.util.ArrayList<ApiCallPair>();
        for (int i = 1; i <= 20; i++) {
            calls.add(call("/api/a", 100 + i, 100, false)); // delay = i
        }
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        EndpointStats s = stats.getFirst();
        // P95 index = ceil(20 * 0.95) - 1 = 19 - 1 = 18 → sorted[18] = 19
        assertEquals(19, s.p95ConnectionDelayMs());
    }

    @Test
    void compute_mixedUpstreamData_excludesNegativeFromAvg() {
        var calls = List.of(
                call("/api/a", 100, 50, false),    // delay = 50, included
                call("/api/a", 200, -1, false),     // no upstream, excluded
                call("/api/a", 300, 200, false)     // delay = 100, included
        );
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        EndpointStats s = stats.getFirst();
        assertEquals(75.0, s.avgConnectionDelayMs(), 0.01); // avg(50, 100)
        assertEquals(3, s.callCount()); // all calls counted
    }

    @Test
    void compute_slowConnectionCount() {
        var calls = List.of(
                call("/api/a", 100, 50, true),
                call("/api/a", 200, 150, false),
                call("/api/a", 300, 100, true)
        );
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        assertEquals(2, stats.getFirst().slowConnectionCount());
    }

    @Test
    void compute_upstreamExceedsDuration_delayIsZero() {
        // Edge case: upstream > total (rare nginx edge)
        var calls = List.of(call("/api/a", 100, 150, false));
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        assertEquals(0.0, stats.getFirst().avgConnectionDelayMs(), 0.01);
        assertEquals(0, stats.getFirst().p95ConnectionDelayMs());
    }

    @Test
    void compute_singleCall_p95EqualsOnlyDelay() {
        var calls = List.of(call("/api/a", 200, 100, false)); // delay = 100
        List<EndpointStats> stats = EndpointStatsCalculator.compute(calls, 1000);
        assertEquals(100, stats.getFirst().p95ConnectionDelayMs());
    }

    @Test
    void compute_emptyList_returnsEmpty() {
        List<EndpointStats> stats = EndpointStatsCalculator.compute(List.of(), 1000);
        assertTrue(stats.isEmpty());
    }
}
