package br.com.fzdevx.domain.model.anomaly;

public record AnomalyResult(
        String signalType,
        String bucketLabel,
        double observedValue,
        double baselineValue,
        double ratio,
        int count,
        long maxInBucket
) {}
