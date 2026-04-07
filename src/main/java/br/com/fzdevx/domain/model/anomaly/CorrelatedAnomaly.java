package br.com.fzdevx.domain.model.anomaly;

import java.util.List;

public record CorrelatedAnomaly(
        String windowStart,
        String windowEnd,
        List<String> signalTypes,
        double score,
        String severity,
        List<String> causalChain,
        List<AnomalyResult> anomalies
) {}
