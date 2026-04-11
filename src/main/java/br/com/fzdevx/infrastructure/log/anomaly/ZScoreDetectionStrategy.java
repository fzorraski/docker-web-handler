package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.BucketStats;
import br.com.fzdevx.domain.shared.MetricExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Z-score-based anomaly detection: flags when (value - mean) / stddev >= threshold.
 * Variance-aware — noisy signals tolerate larger jumps; stable signals flag small deviations.
 * Includes cold-start protection: first MIN_WARM_UP buckets are never flagged.
 */
public class ZScoreDetectionStrategy implements DetectionStrategy {

    private static final int MIN_WARM_UP = 3;
    private static final double EPSILON = 1e-9;

    private record RollingStats(double mean, double stddev, int windowSize) {}

    @Override
    public DetectionResult detect(List<BucketStats> buckets, double threshold, int baselineWindow,
                                  String signalType, String metric) {
        if (buckets == null || buckets.isEmpty()) {
            return new DetectionResult(List.of(), List.of());
        }

        List<BucketStats> updated = new ArrayList<>(buckets.size());
        List<AnomalyResult> anomalies = new ArrayList<>();

        for (int i = 0; i < buckets.size(); i++) {
            BucketStats current = buckets.get(i);
            double value = MetricExtractor.extract(current, metric);

            RollingStats stats = computeRollingStats(buckets, i, baselineWindow, metric);

            double zScore = computeZScore(value, stats, threshold);

            String status;
            if (stats.windowSize < MIN_WARM_UP) {
                status = "normal";
            } else if (zScore >= threshold) {
                status = "ANOMALY";
            } else if (zScore >= threshold * 0.5) {
                status = "elevated";
            } else {
                status = "normal";
            }

            updated.add(current.withStatus(status, stats.mean, zScore));

            if ("ANOMALY".equals(status)) {
                anomalies.add(new AnomalyResult(
                        signalType, current.bucketLabel(), value, stats.mean, zScore,
                        current.count(), current.max()));
            }
        }

        return new DetectionResult(updated, anomalies);
    }

    private RollingStats computeRollingStats(List<BucketStats> buckets, int currentIndex,
                                             int window, String metric) {
        if (currentIndex == 0) return new RollingStats(0.0, 0.0, 0);

        int start = Math.max(0, currentIndex - window);
        int count = 0;
        double sum = 0.0;
        double sumSq = 0.0;

        for (int i = start; i < currentIndex; i++) {
            double val = MetricExtractor.extract(buckets.get(i), metric);
            sum += val;
            sumSq += val * val;
            count++;
        }

        if (count == 0) return new RollingStats(0.0, 0.0, 0);

        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);
        double stddev = Math.sqrt(Math.max(0.0, variance));

        return new RollingStats(mean, stddev, count);
    }

    private double computeZScore(double value, RollingStats stats, double threshold) {
        if (stats.windowSize < MIN_WARM_UP) return 0.0;

        if (stats.stddev < EPSILON) {
            // All prior values identical — use proportional ratio fallback
            if (stats.mean == 0.0) {
                return value > 0 ? threshold : 0.0;
            }
            if (value <= stats.mean) return 0.0;
            // Scale proportionally: 2x mean → threshold, 1.5x → threshold*0.5, etc.
            return Math.min(threshold, (value / stats.mean - 1.0) * threshold);
        }

        double z = (value - stats.mean) / stats.stddev;
        return Math.max(0.0, z); // only positive deviations (spikes)
    }
}
