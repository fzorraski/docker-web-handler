package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.BucketStats;
import br.com.fzdevx.domain.shared.MetricExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Original ratio-based anomaly detection: flags when value / rolling_mean >= threshold.
 * Includes cold-start protection: first MIN_WARM_UP buckets are never flagged as ANOMALY.
 */
public class RatioDetectionStrategy implements DetectionStrategy {

    private static final int MIN_WARM_UP = 3;

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

            double baseline = computeRollingMean(buckets, i, baselineWindow, metric);
            int windowSize = Math.min(i, baselineWindow);

            double ratio;
            if (windowSize < MIN_WARM_UP) {
                ratio = 0.0; // cold-start: insufficient data
            } else if (baseline == 0.0) {
                ratio = value > 0 ? threshold : 0.0;
            } else {
                ratio = value / baseline;
            }

            String status;
            if (windowSize < MIN_WARM_UP) {
                status = "normal";
            } else if (ratio >= threshold) {
                status = "ANOMALY";
            } else if (ratio >= 1.5) {
                status = "elevated";
            } else {
                status = "normal";
            }

            updated.add(current.withStatus(status, baseline, ratio));

            if ("ANOMALY".equals(status)) {
                anomalies.add(new AnomalyResult(
                        signalType, current.bucketLabel(), value, baseline, ratio,
                        current.count(), current.max()));
            }
        }

        return new DetectionResult(updated, anomalies);
    }

    private double computeRollingMean(List<BucketStats> buckets, int currentIndex, int window, String metric) {
        if (currentIndex == 0) return 0.0;
        int start = Math.max(0, currentIndex - window);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < currentIndex; i++) {
            sum += MetricExtractor.extract(buckets.get(i), metric);
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }
}
