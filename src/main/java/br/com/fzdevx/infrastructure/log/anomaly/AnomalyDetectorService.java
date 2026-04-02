package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.BucketStats;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AnomalyDetectorService {

    /**
     * Result of anomaly detection: updated buckets with status and the list of detected anomalies.
     */
    public record DetectionResult(List<BucketStats> buckets, List<AnomalyResult> anomalies) {}

    /**
     * Detect anomalies in bucket stats using a rolling baseline comparison.
     *
     * @param buckets        ordered list of bucket stats
     * @param threshold      ratio threshold to flag as ANOMALY (e.g. 3.0 = 3x baseline)
     * @param baselineWindow number of previous buckets to use for rolling baseline
     * @return updated buckets with status and list of detected anomalies
     */
    public DetectionResult detect(List<BucketStats> buckets, double threshold, int baselineWindow,
                                   String signalType) {
        if (buckets == null || buckets.isEmpty()) {
            return new DetectionResult(List.of(), List.of());
        }

        List<BucketStats> updated = new ArrayList<>(buckets.size());
        List<AnomalyResult> anomalies = new ArrayList<>();

        for (int i = 0; i < buckets.size(); i++) {
            BucketStats current = buckets.get(i);

            // Compute rolling baseline from previous N buckets
            double baseline = computeRollingBaseline(buckets, i, baselineWindow);
            double ratio = computeRatio(current.count(), baseline, threshold);

            String status;
            if (ratio >= threshold) {
                status = "ANOMALY";
            } else if (ratio >= 1.5) {
                status = "elevated";
            } else {
                status = "normal";
            }

            BucketStats updatedBucket = current.withStatus(status, baseline, ratio);
            updated.add(updatedBucket);

            if ("ANOMALY".equals(status)) {
                anomalies.add(new AnomalyResult(
                        signalType,
                        current.bucketLabel(),
                        current.count(),
                        baseline,
                        ratio,
                        current.count(),
                        current.max()
                ));
            }
        }

        return new DetectionResult(updated, anomalies);
    }

    private double computeRollingBaseline(List<BucketStats> buckets, int currentIndex, int window) {
        if (currentIndex == 0) return 0.0;

        int start = Math.max(0, currentIndex - window);
        int count = 0;
        double sum = 0.0;

        for (int i = start; i < currentIndex; i++) {
            sum += buckets.get(i).count();
            count++;
        }

        return count > 0 ? sum / count : 0.0;
    }

    private double computeRatio(int currentCount, double baseline, double threshold) {
        if (baseline == 0.0) {
            // If baseline is zero and current has activity, flag it
            return currentCount > 0 ? threshold : 0.0;
        }
        return currentCount / baseline;
    }
}
