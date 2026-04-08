package br.com.fzdevx.domain.model.anomaly;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record SystemHealthResponse(
        int bucketSize,
        String metric,
        List<String> signalTypes,
        List<String> durationSignals,
        List<HealthBucket> buckets
) {
    /** Signal types that have meaningful numeric duration values (ms). */
    public static final Set<String> DURATION_SIGNAL_TYPES = Set.of(
            "API_LATENCY", "SLOW_QUERY", "GC_PAUSE", "POOL_EXHAUSTION", "JOB_DURATION"
    );

    public record HealthBucket(
            String time,
            long epoch,
            Map<String, Double> values
    ) {}
}
