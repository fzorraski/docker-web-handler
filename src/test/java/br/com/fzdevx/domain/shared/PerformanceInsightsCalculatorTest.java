package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.PerformanceInsights;
import br.com.fzdevx.domain.model.PerformanceInsights.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceInsightsCalculatorTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 30, 10, 0, 0);

    private ApiCallPair call(String endpoint, LocalDateTime requestTime, long durationMs) {
        return new ApiCallPair(
                endpoint, null, "thread-1",
                requestTime,
                requestTime.plusNanos(durationMs * 1_000_000),
                durationMs, -1, false,
                null, null,
                1, 2, "test.log", false
        );
    }

    private ApiCallPair call(String endpoint, LocalDateTime requestTime, long durationMs, boolean slow) {
        return new ApiCallPair(
                endpoint, null, "thread-1",
                requestTime,
                requestTime.plusNanos(durationMs * 1_000_000),
                durationMs, -1, false,
                null, null,
                1, 2, "test.log", slow
        );
    }

    // ---- Empty / null input ----

    @Test
    void nullInputReturnsEmptyResult() {
        PerformanceInsights result = PerformanceInsightsCalculator.compute(null, BASE, BASE.plusMinutes(10));

        assertTrue(result.timeBuckets().isEmpty());
        assertTrue(result.topEndpointsByImpact().isEmpty());
        assertEquals("1m", result.bucketWidth());
        assertEquals(0, result.totalBuckets());
    }

    @Test
    void emptyListReturnsEmptyResult() {
        PerformanceInsights result = PerformanceInsightsCalculator.compute(List.of(), BASE, BASE.plusMinutes(10));

        assertTrue(result.timeBuckets().isEmpty());
        assertTrue(result.topEndpointsByImpact().isEmpty());
    }

    @Test
    void nullTimestampsReturnsEmptyResult() {
        var calls = List.of(call("/api/test", BASE, 100));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, null, null);

        assertTrue(result.timeBuckets().isEmpty());
    }

    // ---- Single API call ----

    @Test
    void singleCallProducesOneBucketWithStats() {
        var calls = List.of(call("/api/users", BASE, 150));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(1));

        // With range <= 1 minute, bucket width is 1m, so we get 2 buckets (start and start+1m)
        assertFalse(result.timeBuckets().isEmpty());

        // Find the bucket that has the call
        TimeBucket populated = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();

        assertEquals(1, populated.requestCount());
        assertEquals(150.0, populated.avgDurationMs(), 0.01);
        assertEquals(150, populated.maxDurationMs());
        assertEquals(150, populated.p95DurationMs());
    }

    // ---- Multiple calls in same bucket ----

    @Test
    void multipleCallsInSameBucketAggregatedCorrectly() {
        var calls = List.of(
                call("/api/users", BASE, 100),
                call("/api/users", BASE.plusSeconds(10), 200),
                call("/api/users", BASE.plusSeconds(20), 300),
                call("/api/users", BASE.plusSeconds(30), 400)
        );
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();

        assertEquals(4, bucket.requestCount());
        assertEquals(250.0, bucket.avgDurationMs(), 0.01);
        assertEquals(400, bucket.maxDurationMs());
        // p95 of [100, 200, 300, 400]: ceil(4 * 0.95) - 1 = ceil(3.8) - 1 = 4 - 1 = 3 -> index 3 -> 400
        assertEquals(400, bucket.p95DurationMs());
    }

    // ---- Bucket width selection ----

    @Test
    void bucketWidth1mForUpTo30Minutes() {
        var calls = List.of(call("/api/test", BASE, 50));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(30));

        assertEquals("1m", result.bucketWidth());
    }

    @Test
    void bucketWidth5mForUpTo4Hours() {
        var calls = List.of(call("/api/test", BASE, 50));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(31));

        assertEquals("5m", result.bucketWidth());
    }

    @Test
    void bucketWidth5mAtExactly4Hours() {
        var calls = List.of(call("/api/test", BASE, 50));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusHours(4));

        assertEquals("5m", result.bucketWidth());
    }

    @Test
    void bucketWidth15mForUpTo24Hours() {
        var calls = List.of(call("/api/test", BASE, 50));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusHours(5));

        assertEquals("15m", result.bucketWidth());
    }

    @Test
    void bucketWidth1hForOver24Hours() {
        var calls = List.of(call("/api/test", BASE, 50));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusHours(25));

        assertEquals("1h", result.bucketWidth());
    }

    // ---- Concurrent peak ----

    @Test
    void concurrentPeakCalculatedForOverlappingCalls() {
        // Three calls that overlap in time
        LocalDateTime t = BASE;
        var calls = List.of(
                new ApiCallPair("/api/a", null, "t-1", t, t.plusSeconds(10), 10000, -1, false, null, null, 1, 2, "test.log", false),
                new ApiCallPair("/api/b", null, "t-2", t.plusSeconds(2), t.plusSeconds(8), 6000, -1, false, null, null, 3, 4, "test.log", false),
                new ApiCallPair("/api/c", null, "t-3", t.plusSeconds(4), t.plusSeconds(12), 8000, -1, false, null, null, 5, 6, "test.log", false)
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();

        // All three overlap between t+4s and t+8s, so peak concurrency = 3
        assertEquals(3, bucket.concurrentPeak());
    }

    @Test
    void concurrentPeakIsOneForNonOverlappingCalls() {
        var calls = List.of(
                call("/api/a", BASE, 100),
                call("/api/b", BASE.plusSeconds(30), 100)
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();

        assertEquals(1, bucket.concurrentPeak());
    }

    // ---- Top endpoints by impact ----

    @Test
    void topEndpointsByImpactSortedByTotalDurationDescending() {
        var calls = List.of(
                call("/api/fast", BASE, 10),
                call("/api/fast", BASE.plusSeconds(1), 10),
                call("/api/fast", BASE.plusSeconds(2), 10),   // total = 30
                call("/api/slow", BASE.plusSeconds(3), 500),  // total = 500
                call("/api/medium", BASE.plusSeconds(4), 200)  // total = 200
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        assertEquals(3, result.topEndpointsByImpact().size());
        assertEquals("/api/slow", result.topEndpointsByImpact().get(0).endpoint());
        assertEquals("/api/medium", result.topEndpointsByImpact().get(1).endpoint());
        assertEquals("/api/fast", result.topEndpointsByImpact().get(2).endpoint());

        assertEquals(500.0, result.topEndpointsByImpact().get(0).totalDurationMs(), 0.01);
        assertEquals(200.0, result.topEndpointsByImpact().get(1).totalDurationMs(), 0.01);
        assertEquals(30.0, result.topEndpointsByImpact().get(2).totalDurationMs(), 0.01);
    }

    @Test
    void topEndpointsByImpactIncludesSlowCount() {
        var calls = List.of(
                call("/api/users", BASE, 100, true),
                call("/api/users", BASE.plusSeconds(1), 50, false),
                call("/api/users", BASE.plusSeconds(2), 200, true)
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        assertEquals(1, result.topEndpointsByImpact().size());
        assertEquals(2, result.topEndpointsByImpact().getFirst().slowCount());
    }

    // ---- Endpoint filtering (computeBucketEndpoints) ----

    @Test
    void computeBucketEndpointsReturnsEndpointsForSpecificBucket() {
        var calls = List.of(
                call("/api/users", BASE, 100),
                call("/api/orders", BASE.plusSeconds(10), 200),
                call("/api/users", BASE.plusMinutes(2), 300)  // different bucket in 5m width
        );

        String bucketTimestamp = BASE.toString();
        List<EndpointBucket> endpoints = PerformanceInsightsCalculator.computeBucketEndpoints(
                calls, BASE, BASE.plusMinutes(5), bucketTimestamp, Integer.MAX_VALUE
        );

        // Only calls within the first 1-minute bucket should be included (since range is 5m => bucket width 1m)
        // Wait: range 5min => bucket width 1m. So first bucket is [BASE, BASE+1m).
        // Only the first two calls (at BASE and BASE+10s) fall in that bucket.
        assertEquals(2, endpoints.size());
    }

    @Test
    void computeBucketEndpointsReturnsEmptyForNullInput() {
        List<EndpointBucket> endpoints = PerformanceInsightsCalculator.computeBucketEndpoints(
                null, BASE, BASE.plusMinutes(5), BASE.toString(), 10
        );

        assertTrue(endpoints.isEmpty());
    }

    // ---- p95 calculation ----

    @Test
    void p95CalculationAccuracy() {
        // 20 calls with durations 1..20
        var calls = new ArrayList<ApiCallPair>();
        for (int i = 1; i <= 20; i++) {
            calls.add(call("/api/test", BASE.plusSeconds(i), i * 10L));
        }

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();

        // Sorted durations: 10, 20, ..., 200
        // p95Idx = ceil(20 * 0.95) - 1 = ceil(19.0) - 1 = 19 - 1 = 18 -> value = 190
        assertEquals(190, bucket.p95DurationMs());
    }

    @Test
    void p95WithSingleElementIsTheElement() {
        var calls = List.of(call("/api/test", BASE, 42));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();

        assertEquals(42, bucket.p95DurationMs());
    }

    // ---- All endpoints returned per bucket ----

    @Test
    void allEndpointsReturnedPerBucket() {
        // Create calls to 10 different endpoints in the same bucket
        var calls = new ArrayList<ApiCallPair>();
        for (int i = 0; i < 10; i++) {
            calls.add(call("/api/endpoint-" + i, BASE.plusSeconds(i), 100 + i));
        }

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();

        // All 10 endpoints should be present (not limited to top 5)
        assertEquals(10, bucket.endpoints().size());
    }

    // ---- Calls with null requestTimestamp are filtered out ----

    @Test
    void callsWithNullRequestTimestampFiltered() {
        var calls = List.of(
                new ApiCallPair("/api/null-ts", null, "t-1", null, null, 100, -1, false, null, null, 1, 2, "test.log", false),
                call("/api/valid", BASE, 200)
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        int totalRequests = result.timeBuckets().stream().mapToInt(TimeBucket::requestCount).sum();
        assertEquals(1, totalRequests);
    }

    // ---- Empty buckets have zero stats ----

    @Test
    void emptyBucketsHaveZeroStats() {
        // Call at BASE, but range goes to BASE+5m so some buckets will be empty
        var calls = List.of(call("/api/test", BASE, 100));
        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        long emptyBuckets = result.timeBuckets().stream()
                .filter(b -> b.requestCount() == 0)
                .count();

        assertTrue(emptyBuckets > 0);

        result.timeBuckets().stream()
                .filter(b -> b.requestCount() == 0)
                .forEach(b -> {
                    assertEquals(0, b.avgDurationMs(), 0.01);
                    assertEquals(0, b.maxDurationMs());
                    assertEquals(0, b.p95DurationMs());
                    assertEquals(0, b.concurrentPeak());
                    assertEquals(-1, b.avgConnectionDelayMs(), 0.01);
                    assertTrue(b.endpoints().isEmpty());
                });
    }

    // ---- Connection delay in time buckets ----

    private ApiCallPair callWithUpstream(String endpoint, LocalDateTime requestTime,
                                         long durationMs, long upstreamMs) {
        return new ApiCallPair(
                endpoint, null, "thread-1",
                requestTime,
                requestTime.plusNanos(durationMs * 1_000_000),
                durationMs, upstreamMs, false,
                null, null,
                1, 2, "test.log", false
        );
    }

    @Test
    void avgConnectionDelay_withUpstreamData() {
        var calls = List.of(
                callWithUpstream("/api/a", BASE, 200, 100),       // delay = 100
                callWithUpstream("/api/b", BASE.plusSeconds(1), 300, 200) // delay = 100
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();
        assertEquals(100.0, bucket.avgConnectionDelayMs(), 0.01);
    }

    @Test
    void avgConnectionDelay_noUpstreamData_returnsNegativeOne() {
        var calls = List.of(
                call("/api/a", BASE, 200),
                call("/api/b", BASE.plusSeconds(1), 300)
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();
        assertEquals(-1, bucket.avgConnectionDelayMs(), 0.01);
    }

    @Test
    void avgConnectionDelay_mixedUpstream_onlyCountsKnown() {
        var calls = List.of(
                callWithUpstream("/api/a", BASE, 200, 100),       // delay = 100
                call("/api/b", BASE.plusSeconds(1), 300),          // no upstream
                callWithUpstream("/api/c", BASE.plusSeconds(2), 400, 200) // delay = 200
        );

        PerformanceInsights result = PerformanceInsightsCalculator.compute(calls, BASE, BASE.plusMinutes(5));

        TimeBucket bucket = result.timeBuckets().stream()
                .filter(b -> b.requestCount() > 0)
                .findFirst().orElseThrow();
        // avg(100, 200) = 150, only the two calls with upstream
        assertEquals(150.0, bucket.avgConnectionDelayMs(), 0.01);
        assertEquals(3, bucket.requestCount()); // all three counted in request count
    }
}
