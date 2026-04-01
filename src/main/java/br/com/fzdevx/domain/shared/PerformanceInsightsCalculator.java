package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.PerformanceInsights;
import br.com.fzdevx.domain.model.PerformanceInsights.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public final class PerformanceInsightsCalculator {

    private PerformanceInsightsCalculator() {}

    private record BucketConfig(int bucketMinutes, String bucketWidth) {}

    private static BucketConfig determineBucketConfig(LocalDateTime timeRangeStart, LocalDateTime timeRangeEnd) {
        long rangeMinutes = Duration.between(timeRangeStart, timeRangeEnd).toMinutes();
        if (rangeMinutes <= 30) return new BucketConfig(1, "1m");
        if (rangeMinutes <= 240) return new BucketConfig(5, "5m");
        if (rangeMinutes <= 1440) return new BucketConfig(15, "15m");
        return new BucketConfig(60, "1h");
    }

    public static PerformanceInsights compute(List<ApiCallPair> apiCalls,
                                               LocalDateTime timeRangeStart,
                                               LocalDateTime timeRangeEnd) {
        if (apiCalls == null || apiCalls.isEmpty() || timeRangeStart == null || timeRangeEnd == null) {
            return new PerformanceInsights(List.of(), List.of(), "1m", 0);
        }

        // Filter calls with valid timestamps
        List<ApiCallPair> validCalls = apiCalls.stream()
                .filter(c -> c.requestTimestamp() != null)
                .sorted(Comparator.comparing(ApiCallPair::requestTimestamp))
                .toList();

        if (validCalls.isEmpty()) {
            return new PerformanceInsights(List.of(), List.of(), "1m", 0);
        }

        // Determine bucket width
        BucketConfig config = determineBucketConfig(timeRangeStart, timeRangeEnd);
        int bucketMinutes = config.bucketMinutes();
        String bucketWidth = config.bucketWidth();

        // Create buckets
        List<LocalDateTime> bucketStarts = new ArrayList<>();
        LocalDateTime current = timeRangeStart;
        while (!current.isAfter(timeRangeEnd)) {
            bucketStarts.add(current);
            current = current.plusMinutes(bucketMinutes);
        }

        // Assign calls to buckets
        List<List<ApiCallPair>> bucketCalls = new ArrayList<>();
        for (int i = 0; i < bucketStarts.size(); i++) {
            bucketCalls.add(new ArrayList<>());
        }

        for (ApiCallPair call : validCalls) {
            int bucketIdx = (int) (Duration.between(timeRangeStart, call.requestTimestamp()).toMinutes() / bucketMinutes);
            if (bucketIdx >= bucketStarts.size()) bucketIdx = bucketStarts.size() - 1;
            if (bucketIdx < 0) bucketIdx = 0;
            bucketCalls.get(bucketIdx).add(call);
        }

        // Compute per-bucket stats
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        List<TimeBucket> timeBuckets = new ArrayList<>();
        for (int i = 0; i < bucketStarts.size(); i++) {
            List<ApiCallPair> calls = bucketCalls.get(i);
            if (calls.isEmpty()) {
                timeBuckets.add(new TimeBucket(bucketStarts.get(i).format(fmt), 0, 0, 0, 0, 0, List.of()));
                continue;
            }

            int count = calls.size();
            double avg = calls.stream().mapToLong(ApiCallPair::durationMs).average().orElse(0);
            long max = calls.stream().mapToLong(ApiCallPair::durationMs).max().orElse(0);
            long[] durations = calls.stream().mapToLong(ApiCallPair::durationMs).sorted().toArray();
            int p95Idx = Math.max(0, (int) Math.ceil(durations.length * 0.95) - 1);
            long p95 = durations[p95Idx];

            // Concurrency peak: sweep line
            int concurrentPeak = computeConcurrencyPeak(calls);

            // All endpoints in this bucket, sorted by count desc
            List<EndpointBucket> topEndpoints = computeTopEndpointsInBucket(calls, Integer.MAX_VALUE);

            timeBuckets.add(new TimeBucket(
                    bucketStarts.get(i).format(fmt),
                    count, avg, p95, max, concurrentPeak, topEndpoints
            ));
        }

        // Top 10 endpoints by total impact
        List<EndpointImpact> topByImpact = computeTopEndpointsByImpact(validCalls, 10);

        return new PerformanceInsights(timeBuckets, topByImpact, bucketWidth, timeBuckets.size());
    }

    /**
     * Returns all endpoints for a specific bucket identified by its timestamp string.
     */
    public static List<EndpointBucket> computeBucketEndpoints(List<ApiCallPair> apiCalls,
                                                               LocalDateTime timeRangeStart,
                                                               LocalDateTime timeRangeEnd,
                                                               String bucketTimestamp,
                                                               int limit) {
        if (apiCalls == null || apiCalls.isEmpty() || timeRangeStart == null || timeRangeEnd == null) {
            return List.of();
        }

        int bucketMinutes = determineBucketConfig(timeRangeStart, timeRangeEnd).bucketMinutes();

        LocalDateTime bucketStart = LocalDateTime.parse(bucketTimestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        LocalDateTime bucketEnd = bucketStart.plusMinutes(bucketMinutes);

        List<ApiCallPair> bucketCalls = apiCalls.stream()
                .filter(c -> c.requestTimestamp() != null
                        && !c.requestTimestamp().isBefore(bucketStart)
                        && c.requestTimestamp().isBefore(bucketEnd))
                .toList();

        return computeTopEndpointsInBucket(bucketCalls, limit);
    }

    private static int computeConcurrencyPeak(List<ApiCallPair> calls) {
        // Create events: +1 at request start, -1 at response end
        List<long[]> events = new ArrayList<>();
        for (ApiCallPair call : calls) {
            if (call.requestTimestamp() == null) continue;
            long start = toEpochMs(call.requestTimestamp());
            long end = call.responseTimestamp() != null ? toEpochMs(call.responseTimestamp()) : start + call.durationMs();
            events.add(new long[]{start, 1});
            events.add(new long[]{end, -1});
        }
        events.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));

        int peak = 0, current = 0;
        for (long[] event : events) {
            current += (int) event[1];
            peak = Math.max(peak, current);
        }
        return peak;
    }

    private static List<EndpointBucket> computeTopEndpointsInBucket(List<ApiCallPair> calls, int topN) {
        Map<String, List<ApiCallPair>> byEndpoint = calls.stream()
                .collect(Collectors.groupingBy(ApiCallPair::endpoint, LinkedHashMap::new, Collectors.toList()));

        return byEndpoint.entrySet().stream()
                .map(e -> {
                    long[] durations = e.getValue().stream().mapToLong(ApiCallPair::durationMs).sorted().toArray();
                    int p95Idx = Math.max(0, (int) Math.ceil(durations.length * 0.95) - 1);
                    long p95 = durations.length > 0 ? durations[p95Idx] : 0;
                    return new EndpointBucket(
                            e.getKey(),
                            e.getValue().size(),
                            e.getValue().stream().mapToLong(ApiCallPair::durationMs).average().orElse(0),
                            p95
                    );
                })
                .sorted(Comparator.comparingInt(EndpointBucket::count).reversed())
                .limit(topN)
                .toList();
    }

    private static List<EndpointImpact> computeTopEndpointsByImpact(List<ApiCallPair> calls, int topN) {
        Map<String, List<ApiCallPair>> byEndpoint = calls.stream()
                .collect(Collectors.groupingBy(ApiCallPair::endpoint, LinkedHashMap::new, Collectors.toList()));

        return byEndpoint.entrySet().stream()
                .map(e -> {
                    List<ApiCallPair> epCalls = e.getValue();
                    int count = epCalls.size();
                    double avg = epCalls.stream().mapToLong(ApiCallPair::durationMs).average().orElse(0);
                    double total = epCalls.stream().mapToLong(ApiCallPair::durationMs).sum();
                    long[] durations = epCalls.stream().mapToLong(ApiCallPair::durationMs).sorted().toArray();
                    int p95Idx = Math.max(0, (int) Math.ceil(durations.length * 0.95) - 1);
                    long p95 = durations.length > 0 ? durations[p95Idx] : 0;
                    int slowCount = (int) epCalls.stream().filter(ApiCallPair::slow).count();
                    return new EndpointImpact(e.getKey(), count, avg, total, p95, slowCount);
                })
                .sorted(Comparator.comparingDouble(EndpointImpact::totalDurationMs).reversed())
                .limit(topN)
                .toList();
    }

    private static long toEpochMs(LocalDateTime dt) {
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
