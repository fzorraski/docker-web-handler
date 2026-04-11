/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — system health timeline aggregation),
 *        D (depends on SignalExtractionPort interface)
 * Behavior: identical to original LogAnalyzerController#getSystemHealth
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.SignalExtractionPort;
import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.model.anomaly.*;
import br.com.fzdevx.domain.shared.MetricExtractor;
import br.com.fzdevx.domain.shared.TimeBucketAggregator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

@ApplicationScoped
public class GetSystemHealthUseCase {

    @Inject
    SignalExtractionPort signalExtractor;

    public SystemHealthResponse execute(LogAnalysis analysis, int bucketSize, String metric) {
        if (analysis.getTimeRangeStart() == null || analysis.getTimeRangeEnd() == null) {
            return new SystemHealthResponse(bucketSize, metric, List.of(), List.of(), List.of());
        }

        Map<SignalType, List<Signal>> allSignals = signalExtractor.extractAll(
                analysis.getAllLines(), analysis.getApiCalls(),
                analysis.getJobExecutions(), analysis.getOrphanRequests());

        // Bucket each signal type independently
        Map<String, List<BucketStats>> bucketsByType = new LinkedHashMap<>();
        for (var entry : allSignals.entrySet()) {
            List<BucketStats> typeBuckets = TimeBucketAggregator.aggregate(
                    entry.getValue(), bucketSize,
                    analysis.getTimeRangeStart(), analysis.getTimeRangeEnd());
            bucketsByType.put(entry.getKey().name(), typeBuckets);
        }

        // Merge by epoch into unified timeline
        var epochMap = new TreeMap<Long, Map<String, Double>>();
        var labelMap = new TreeMap<Long, String>();
        for (var entry : bucketsByType.entrySet()) {
            String typeName = entry.getKey();
            // Use selected metric for duration signals, always count for the rest
            String effectiveMetric = SystemHealthResponse.DURATION_SIGNAL_TYPES.contains(typeName) ? metric : "count";
            for (BucketStats b : entry.getValue()) {
                epochMap.computeIfAbsent(b.bucketEpoch(), _ -> new LinkedHashMap<>())
                        .put(typeName, MetricExtractor.extract(b, effectiveMetric));
                labelMap.putIfAbsent(b.bucketEpoch(), b.bucketLabel());
            }
        }

        List<String> signalTypes = new ArrayList<>(bucketsByType.keySet());
        List<SystemHealthResponse.HealthBucket> healthBuckets = epochMap.entrySet().stream()
                .map(e -> new SystemHealthResponse.HealthBucket(
                        labelMap.get(e.getKey()), e.getKey(), e.getValue()))
                .toList();

        List<String> durationSignals = signalTypes.stream()
                .filter(SystemHealthResponse.DURATION_SIGNAL_TYPES::contains).toList();
        return new SystemHealthResponse(bucketSize, metric, signalTypes, durationSignals, healthBuckets);
    }
}
