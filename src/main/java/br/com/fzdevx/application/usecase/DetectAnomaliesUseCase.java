/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — anomaly detection pipeline orchestration),
 *        D (depends on port interfaces for signal extraction and anomaly detection)
 * Behavior: identical to original LogAnalyzerController#getAnomalyDetection and #getAvailableSignalTypes
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.AnomalyDetectionPort;
import br.com.fzdevx.application.port.SignalExtractionPort;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.domain.model.anomaly.*;
import br.com.fzdevx.domain.shared.CorrelationDetector;
import br.com.fzdevx.domain.shared.MetricExtractor;
import br.com.fzdevx.domain.shared.TimeBucketAggregator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

@ApplicationScoped
public class DetectAnomaliesUseCase {

    public static final Set<String> VALID_METRICS = Set.of("count", "p95", "max", "avg");

    @Inject
    SignalExtractionPort signalExtractor;

    @Inject
    AnomalyDetectionPort anomalyDetector;

    public Set<String> getValidMethods() {
        return anomalyDetector.validMethods();
    }

    public AnomalyDetectionResponse detect(LogAnalysis analysis,
                                           SignalType signalType, int bucketSize, double threshold,
                                           int baselineWindow, String metric, String method) {
        String signalTypeName = signalType.name();

        // Extract signals
        List<Signal> signals = signalExtractor.extract(analysis.getAllLines(), signalType,
                analysis.getApiCalls(), analysis.getJobExecutions(), analysis.getOrphanRequests());

        if (analysis.getTimeRangeStart() == null || analysis.getTimeRangeEnd() == null) {
            return new AnomalyDetectionResponse(
                    signalTypeName, metric, method, bucketSize, threshold, 0, 0, 0, "", 0,
                    List.of(), List.of(), List.of()
            );
        }

        // Aggregate into time buckets
        List<BucketStats> buckets = TimeBucketAggregator.aggregate(
                signals, bucketSize, analysis.getTimeRangeStart(), analysis.getTimeRangeEnd());

        // Detect anomalies
        var detection = anomalyDetector.detect(
                buckets, threshold, baselineWindow, signalTypeName, metric, method);

        List<BucketStats> updatedBuckets = detection.buckets();
        List<AnomalyResult> anomalies = detection.anomalies();

        // Compute metric-aware summary stats
        long peakValue = updatedBuckets.stream()
                .mapToLong(b -> (long) MetricExtractor.extract(b, metric))
                .max().orElse(0);
        String peakBucketLabel = updatedBuckets.stream()
                .max(Comparator.comparingDouble(b -> MetricExtractor.extract(b, metric)))
                .map(BucketStats::bucketLabel)
                .orElse("");
        long p95Value = computeP95FromBuckets(updatedBuckets, metric);

        // Correlation detection (extract all signal types and detect)
        List<CorrelatedAnomaly> correlations = List.of();
        if (!anomalies.isEmpty()) {
            Map<SignalType, List<Signal>> allSignals = signalExtractor.extractAll(
                    analysis.getAllLines(), analysis.getApiCalls(),
                    analysis.getJobExecutions(), analysis.getOrphanRequests());
            Map<String, List<AnomalyResult>> allAnomaliesByType = new LinkedHashMap<>();
            allAnomaliesByType.put(signalTypeName, anomalies);

            for (Map.Entry<SignalType, List<Signal>> entry : allSignals.entrySet()) {
                if (entry.getKey().name().equals(signalTypeName)) continue;

                List<BucketStats> otherBuckets = TimeBucketAggregator.aggregate(
                        entry.getValue(), bucketSize,
                        analysis.getTimeRangeStart(), analysis.getTimeRangeEnd());
                var otherDetection = anomalyDetector.detect(
                        otherBuckets, threshold, baselineWindow, entry.getKey().name(), metric, method);

                if (!otherDetection.anomalies().isEmpty()) {
                    allAnomaliesByType.put(entry.getKey().name(), otherDetection.anomalies());
                }
            }

            if (allAnomaliesByType.size() >= 2) {
                correlations = CorrelationDetector.detect(allAnomaliesByType, 6);
            }
        }

        return new AnomalyDetectionResponse(
                signalTypeName, metric, method, bucketSize, threshold,
                updatedBuckets.size(),
                (int) updatedBuckets.stream().filter(b -> "ANOMALY".equals(b.status())).count(),
                peakValue, peakBucketLabel, p95Value,
                updatedBuckets, anomalies, correlations
        );
    }

    public List<String> getAvailableSignalTypes(LogAnalysis analysis) {
        List<SignalType> types = signalExtractor.detectAvailableTypes(
                analysis.getAllLines(), analysis.getApiCalls(),
                analysis.getJobExecutions(), analysis.getOrphanRequests());
        return types.stream().map(SignalType::name).toList();
    }

    private long computeP95FromBuckets(List<BucketStats> buckets, String metric) {
        long[] values = buckets.stream()
                .mapToLong(b -> (long) MetricExtractor.extract(b, metric))
                .filter(c -> c > 0)
                .sorted()
                .toArray();
        if (values.length == 0) return 0;
        int idx = Math.max(0, (int) Math.ceil(values.length * 0.95) - 1);
        return values[idx];
    }
}
