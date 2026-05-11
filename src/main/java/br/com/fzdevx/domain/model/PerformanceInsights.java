package br.com.fzdevx.domain.model;

import java.util.List;

public record PerformanceInsights(
        List<TimeBucket> timeBuckets,
        List<EndpointImpact> topEndpointsByImpact,
        String bucketWidth,
        int totalBuckets
) {
    public record TimeBucket(
            String timestamp,
            int requestCount,
            double avgDurationMs,
            long p95DurationMs,
            long maxDurationMs,
            int concurrentPeak,
            double avgConnectionDelayMs,
            List<EndpointBucket> endpoints
    ) {}

    public record EndpointBucket(
            String endpoint,
            int count,
            double avgDurationMs,
            long p95DurationMs
    ) {}

    public record EndpointImpact(
            String endpoint,
            int callCount,
            double avgDurationMs,
            double totalDurationMs,
            long p95DurationMs,
            int slowCount
    ) {}
}
