package br.com.fzdevx.domain.model.anomaly;

public record BucketStats(
        String bucketLabel,
        long bucketEpoch,
        int count,
        long sum,
        long min,
        long max,
        long p95,
        String status,
        double baseline,
        double ratio
) {

    public double avg() {
        return count > 0 ? (double) sum / count : 0.0;
    }

    public BucketStats withStatus(String status, double baseline, double ratio) {
        return new BucketStats(bucketLabel, bucketEpoch, count, sum, min, max, p95, status, baseline, ratio);
    }
}
