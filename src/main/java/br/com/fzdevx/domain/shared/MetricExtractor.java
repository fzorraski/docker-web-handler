package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.anomaly.BucketStats;

import java.util.Set;

/**
 * Extracts a metric value from a BucketStats based on the metric name.
 */
public final class MetricExtractor {

    public static final Set<String> VALID_METRICS = Set.of("count", "p95", "max", "avg");

    private MetricExtractor() {}

    public static double extract(BucketStats bucket, String metric) {
        return switch (metric) {
            case "count" -> bucket.count();
            case "p95" -> bucket.p95();
            case "max" -> bucket.max();
            case "avg" -> bucket.avg();
            default -> throw new IllegalArgumentException("Invalid metric: " + metric);
        };
    }
}
