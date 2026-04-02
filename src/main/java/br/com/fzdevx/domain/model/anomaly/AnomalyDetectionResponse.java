package br.com.fzdevx.domain.model.anomaly;

import java.util.List;

public record AnomalyDetectionResponse(
        String signalType,
        int bucketSize,
        double threshold,
        int totalBuckets,
        int anomalyBuckets,
        long peakValue,
        String peakBucketLabel,
        long p95Value,
        List<BucketStats> buckets,
        List<AnomalyResult> anomalies,
        List<CorrelatedAnomaly> correlations
) {}
