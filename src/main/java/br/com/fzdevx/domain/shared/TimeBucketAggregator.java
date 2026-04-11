package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.anomaly.BucketStats;
import br.com.fzdevx.domain.model.anomaly.Signal;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class TimeBucketAggregator {

    private static final DateTimeFormatter BUCKET_LABEL_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private TimeBucketAggregator() {}

    /**
     * Aggregate signals into time buckets of the specified size.
     * Creates empty buckets for gaps to ensure a continuous timeline.
     *
     * @param signals          list of signals to aggregate
     * @param bucketSizeSeconds bucket size in seconds
     * @param start            start of the time range
     * @param end              end of the time range
     * @return ordered list of bucket stats
     */
    public static List<BucketStats> aggregate(List<Signal> signals, int bucketSizeSeconds,
                                               LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || bucketSizeSeconds <= 0) return List.of();

        long startEpoch = toEpochSeconds(start);
        long endEpoch = toEpochSeconds(end);

        // Align start/end to bucket boundaries
        long alignedStart = (startEpoch / bucketSizeSeconds) * bucketSizeSeconds;
        long alignedEnd = ((endEpoch / bucketSizeSeconds) + 1) * bucketSizeSeconds;

        // Group signals into buckets
        Map<Long, List<Long>> bucketValues = new TreeMap<>();

        if (signals != null) {
            for (Signal signal : signals) {
                if (signal.timestamp() == null) continue;
                long epoch = toEpochSeconds(signal.timestamp());
                long bucketKey = (epoch / bucketSizeSeconds) * bucketSizeSeconds;

                // Clamp to range
                if (bucketKey < alignedStart) bucketKey = alignedStart;
                if (bucketKey >= alignedEnd) bucketKey = alignedEnd - bucketSizeSeconds;

                bucketValues.computeIfAbsent(bucketKey, k -> new ArrayList<>())
                        .add(signal.numericValue() != null ? signal.numericValue() : 1L);
            }
        }

        // Build result with gap-filled buckets
        List<BucketStats> result = new ArrayList<>();
        for (long epoch = alignedStart; epoch < alignedEnd; epoch += bucketSizeSeconds) {
            List<Long> values = bucketValues.get(epoch);
            if (values == null || values.isEmpty()) {
                result.add(new BucketStats(
                        formatBucketLabel(epoch),
                        epoch, 0, 0, 0, 0, 0,
                        "normal", 0.0, 0.0
                ));
            } else {
                int count = values.size();
                long sum = values.stream().mapToLong(Long::longValue).sum();
                long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
                long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
                long p95 = computeP95(values);

                result.add(new BucketStats(
                        formatBucketLabel(epoch),
                        epoch, count, sum, min, max, p95,
                        "normal", 0.0, 0.0
                ));
            }
        }

        return result;
    }

    private static long computeP95(List<Long> values) {
        if (values.isEmpty()) return 0;
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int idx = Math.max(0, (int) Math.ceil(sorted.length * 0.95) - 1);
        return sorted[idx];
    }

    private static String formatBucketLabel(long epochSeconds) {
        return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(epochSeconds),
                ZoneId.systemDefault()
        ).format(BUCKET_LABEL_FMT);
    }

    private static long toEpochSeconds(LocalDateTime dt) {
        return dt.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
