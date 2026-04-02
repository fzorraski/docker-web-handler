package br.com.fzdevx.domain.model.anomaly;

import java.util.List;

public record CorrelatedAnomaly(
        String windowStart,
        String windowEnd,
        List<String> signalTypes,
        double score,
        List<AnomalyResult> anomalies
) {}
