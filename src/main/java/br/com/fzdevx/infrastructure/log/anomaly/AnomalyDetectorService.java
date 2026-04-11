package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.application.port.AnomalyDetectionPort;
import br.com.fzdevx.domain.model.anomaly.BucketStats;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class AnomalyDetectorService implements AnomalyDetectionPort {

    public static final Set<String> VALID_METHODS = Set.of("ratio", "zscore");

    @Override
    public Set<String> validMethods() {
        return VALID_METHODS;
    }

    private static final Map<String, DetectionStrategy> STRATEGIES = Map.of(
            "ratio", new RatioDetectionStrategy(),
            "zscore", new ZScoreDetectionStrategy()
    );

    /**
     * Legacy overload — defaults to ratio method and count metric.
     */
    public DetectionStrategy.DetectionResult detect(java.util.List<BucketStats> buckets, double threshold,
                                                     int baselineWindow, String signalType) {
        return detectInternal(buckets, threshold, baselineWindow, signalType, "count", "ratio");
    }

    /**
     * Detect anomalies using the specified method and metric.
     *
     * @param method "ratio" (original rolling-mean ratio) or "zscore" (z-score with variance)
     * @param metric "count", "p95", "max", or "avg"
     */
    private DetectionStrategy.DetectionResult detectInternal(java.util.List<BucketStats> buckets, double threshold,
                                                            int baselineWindow, String signalType,
                                                            String metric, String method) {
        DetectionStrategy strategy = STRATEGIES.get(method);
        if (strategy == null) {
            throw new IllegalArgumentException("Invalid detection method: " + method);
        }
        return strategy.detect(buckets, threshold, baselineWindow, signalType, metric);
    }

    @Override
    public AnomalyDetectionPort.DetectionResult detect(java.util.List<BucketStats> buckets, double threshold,
                                                        int baselineWindow, String signalType,
                                                        String metric, String method) {
        var result = detectInternal(buckets, threshold, baselineWindow, signalType, metric, method);
        return new AnomalyDetectionPort.DetectionResult(result.buckets(), result.anomalies());
    }
}
