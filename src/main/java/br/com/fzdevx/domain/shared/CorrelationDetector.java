package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.CorrelatedAnomaly;

import java.util.*;
import java.util.stream.Collectors;

public final class CorrelationDetector {

    private CorrelationDetector() {}

    // Causal ordering: lower index = more likely root cause.
    // When two signal types co-occur in the same bucket, the one with lower priority index comes first.
    private static final Map<String, Integer> CAUSAL_PRIORITY;
    static {
        var types = List.of(
                "OOM", "DEADLOCK", "GC_PAUSE", "POOL_EXHAUSTION", "POOL_LEAK",
                "THREAD_REJECTION", "SLOW_QUERY", "SQL_EXCEPTION", "NPE",
                "HTTP_ERROR", "API_COUNT", "API_LATENCY", "JOB_DURATION", "ORPHAN_REQUEST", "ERROR_COUNT"
        );
        var map = new HashMap<String, Integer>();
        for (int i = 0; i < types.size(); i++) map.put(types.get(i), i);
        CAUSAL_PRIORITY = Map.copyOf(map);
    }

    /**
     * Detect correlated anomalies across different signal types within a time window.
     * <p>
     * Improvements over simple grouping:
     * <ul>
     *   <li>Temporal proximity scoring: anomalies in the same bucket score higher than distant ones</li>
     *   <li>Severity-weighted scoring: factors in total anomaly count and combined ratios</li>
     *   <li>Causal direction hints: orders signal types by likely causality</li>
     * </ul>
     */
    public static List<CorrelatedAnomaly> detect(Map<String, List<AnomalyResult>> anomaliesByType,
                                                  int windowMinutes) {
        if (anomaliesByType == null || anomaliesByType.size() < 2) return List.of();

        List<AnomalyResult> allAnomalies = anomaliesByType.values().stream()
                .flatMap(Collection::stream)
                .toList();

        if (allAnomalies.isEmpty()) return List.of();

        Map<String, List<AnomalyResult>> byBucket = allAnomalies.stream()
                .collect(Collectors.groupingBy(AnomalyResult::bucketLabel, LinkedHashMap::new, Collectors.toList()));

        List<String> bucketLabels = new ArrayList<>(byBucket.keySet());
        Collections.sort(bucketLabels);

        List<CorrelatedAnomaly> correlations = new ArrayList<>();
        Set<String> processedBuckets = new HashSet<>();

        for (int i = 0; i < bucketLabels.size(); i++) {
            String startBucket = bucketLabels.get(i);
            if (processedBuckets.contains(startBucket)) continue;

            List<AnomalyResult> windowAnomalies = new ArrayList<>(byBucket.get(startBucket));
            String endBucket = startBucket;
            processedBuckets.add(startBucket);

            for (int j = i + 1; j < bucketLabels.size() && j <= i + windowMinutes; j++) {
                String nextBucket = bucketLabels.get(j);
                windowAnomalies.addAll(byBucket.get(nextBucket));
                endBucket = nextBucket;
                processedBuckets.add(nextBucket);
            }

            List<String> distinctTypes = windowAnomalies.stream()
                    .map(AnomalyResult::signalType)
                    .distinct()
                    .sorted()
                    .toList();

            if (distinctTypes.size() >= 2) {
                double score = computeScore(windowAnomalies, distinctTypes, startBucket);
                String severity = classifySeverity(score);
                List<String> causalChain = buildCausalChain(windowAnomalies, startBucket);

                correlations.add(new CorrelatedAnomaly(
                        startBucket, endBucket, distinctTypes,
                        Math.round(score * 100.0) / 100.0,
                        severity, causalChain, windowAnomalies
                ));
            }
        }

        correlations.sort(Comparator.comparingDouble(CorrelatedAnomaly::score).reversed());
        return correlations;
    }

    private static double computeScore(List<AnomalyResult> anomalies, List<String> distinctTypes, String startBucket) {
        double sumRatios = anomalies.stream().mapToDouble(AnomalyResult::ratio).sum();
        int anomalyCount = anomalies.size();

        long inStartBucket = anomalies.stream()
                .filter(a -> startBucket.equals(a.bucketLabel()))
                .count();
        double proximityFactor = 1.0 + 0.5 * ((double) inStartBucket / anomalyCount);

        double avgRatio = sumRatios / anomalyCount;
        return distinctTypes.size() * avgRatio * proximityFactor;
    }

    private static String classifySeverity(double score) {
        if (score >= 20.0) return "critical";
        if (score >= 10.0) return "high";
        if (score >= 5.0) return "medium";
        return "low";
    }

    private static List<String> buildCausalChain(List<AnomalyResult> anomalies, String startBucket) {
        Map<String, String> earliestBucketByType = new LinkedHashMap<>();
        for (AnomalyResult a : anomalies) {
            earliestBucketByType.merge(a.signalType(), a.bucketLabel(),
                    (existing, next) -> existing.compareTo(next) <= 0 ? existing : next);
        }

        return earliestBucketByType.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, String>, String>comparing(Map.Entry::getValue)
                        .thenComparingInt(e -> CAUSAL_PRIORITY.getOrDefault(e.getKey(), CAUSAL_PRIORITY.size())))
                .map(Map.Entry::getKey)
                .toList();
    }
}
