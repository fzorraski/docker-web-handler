package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.CorrelatedAnomaly;

import java.util.*;
import java.util.stream.Collectors;

public final class CorrelationDetector {

    private CorrelationDetector() {}

    /**
     * Detect correlated anomalies across different signal types within a time window.
     * Groups anomalies from different signal types that occur within windowMinutes of each other.
     *
     * @param anomaliesByType anomalies grouped by signal type
     * @param windowMinutes   time window in minutes to consider anomalies as correlated
     * @return list of correlated anomaly groups
     */
    public static List<CorrelatedAnomaly> detect(Map<String, List<AnomalyResult>> anomaliesByType,
                                                  int windowMinutes) {
        if (anomaliesByType == null || anomaliesByType.size() < 2) return List.of();

        // Flatten all anomalies with their bucket labels
        List<AnomalyResult> allAnomalies = anomaliesByType.values().stream()
                .flatMap(Collection::stream)
                .toList();

        if (allAnomalies.isEmpty()) return List.of();

        // Group by bucket label (anomalies in the same or adjacent buckets)
        Map<String, List<AnomalyResult>> byBucket = allAnomalies.stream()
                .collect(Collectors.groupingBy(AnomalyResult::bucketLabel, LinkedHashMap::new, Collectors.toList()));

        // Find windows where multiple signal types have anomalies
        List<String> bucketLabels = new ArrayList<>(byBucket.keySet());
        Collections.sort(bucketLabels);

        List<CorrelatedAnomaly> correlations = new ArrayList<>();
        Set<String> processedBuckets = new HashSet<>();

        for (int i = 0; i < bucketLabels.size(); i++) {
            String startBucket = bucketLabels.get(i);
            if (processedBuckets.contains(startBucket)) continue;

            // Collect anomalies within the window
            List<AnomalyResult> windowAnomalies = new ArrayList<>(byBucket.get(startBucket));
            String endBucket = startBucket;
            processedBuckets.add(startBucket);

            // Look ahead for nearby buckets within the window
            // Since we only have labels (HH:mm), use sequential proximity as approximation
            for (int j = i + 1; j < bucketLabels.size() && j <= i + windowMinutes; j++) {
                String nextBucket = bucketLabels.get(j);
                windowAnomalies.addAll(byBucket.get(nextBucket));
                endBucket = nextBucket;
                processedBuckets.add(nextBucket);
            }

            // Check if multiple signal types are present
            List<String> distinctTypes = windowAnomalies.stream()
                    .map(AnomalyResult::signalType)
                    .distinct()
                    .sorted()
                    .toList();

            if (distinctTypes.size() >= 2) {
                double maxRatio = windowAnomalies.stream()
                        .mapToDouble(AnomalyResult::ratio)
                        .max()
                        .orElse(0.0);
                double score = distinctTypes.size() * maxRatio;

                correlations.add(new CorrelatedAnomaly(
                        startBucket,
                        endBucket,
                        distinctTypes,
                        Math.round(score * 100.0) / 100.0,
                        windowAnomalies
                ));
            }
        }

        // Sort by score descending
        correlations.sort(Comparator.comparingDouble(CorrelatedAnomaly::score).reversed());
        return correlations;
    }
}
