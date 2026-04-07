package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.BucketStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnomalyDetectorServiceTest {

    private AnomalyDetectorService service;

    @BeforeEach
    void setUp() {
        service = new AnomalyDetectorService();
    }

    private BucketStats bucket(String label, long epoch, int count) {
        return new BucketStats(label, epoch, count, count, count > 0 ? 1 : 0, count, count,
                "normal", 0.0, 0.0);
    }

    private BucketStats bucketWithStats(String label, long epoch, int count, long sum, long min, long max, long p95) {
        return new BucketStats(label, epoch, count, sum, min, max, p95, "normal", 0.0, 0.0);
    }

    // ---- Legacy overload (defaults to ratio + count) ----

    @Test
    void detect_emptyBuckets_returnsEmptyResult() {
        var result = service.detect(List.of(), 3.0, 8, "ERROR_COUNT");
        assertTrue(result.buckets().isEmpty());
        assertTrue(result.anomalies().isEmpty());
    }

    @Test
    void detect_nullBuckets_returnsEmptyResult() {
        var result = service.detect(null, 3.0, 8, "ERROR_COUNT");
        assertTrue(result.buckets().isEmpty());
        assertTrue(result.anomalies().isEmpty());
    }

    // ---- Ratio strategy tests ----

    @Test
    void ratio_coldStart_firstThreeBucketsNeverFlagged() {
        var buckets = List.of(
                bucket("10:00", 1000, 100),
                bucket("10:05", 1300, 100),
                bucket("10:10", 1600, 100),
                bucket("10:15", 1900, 1),
                bucket("10:20", 2200, 1),
                bucket("10:25", 2500, 1)
        );

        var result = service.detect(buckets, 3.0, 8, "ERROR_COUNT", "count", "ratio");

        assertEquals("normal", result.buckets().get(0).status());
        assertEquals("normal", result.buckets().get(1).status());
        assertEquals("normal", result.buckets().get(2).status());
    }

    @Test
    void ratio_uniformBuckets_allNormal() {
        var buckets = List.of(
                bucket("10:00", 1000, 5),
                bucket("10:05", 1300, 5),
                bucket("10:10", 1600, 5),
                bucket("10:15", 1900, 5)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "ratio");

        for (var b : result.buckets()) {
            assertEquals("normal", b.status());
        }
    }

    @Test
    void ratio_spikeDetectedAsAnomaly() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 2),
                bucket("10:10", 1600, 2),
                bucket("10:15", 1900, 2),
                bucket("10:20", 2200, 100)
        );

        var result = service.detect(buckets, 3.0, 4, "ERROR_COUNT", "count", "ratio");

        assertEquals("ANOMALY", result.buckets().get(4).status());
        assertTrue(result.anomalies().stream()
                .anyMatch(a -> "10:20".equals(a.bucketLabel())));
    }

    @Test
    void ratio_elevatedStatus() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 10),
                bucket("10:20", 2200, 20) // ratio = 20/10 = 2.0
        );

        var result = service.detect(buckets, 3.0, 4, "ERROR_COUNT", "count", "ratio");

        assertEquals("elevated", result.buckets().get(4).status());
        assertTrue(result.anomalies().isEmpty() ||
                result.anomalies().stream().noneMatch(a -> "10:20".equals(a.bucketLabel())));
    }

    @Test
    void ratio_setsBaselineAndRatioOnBuckets() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 10)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "ratio");

        // Index 3: baseline = avg of [0, 10, 10] = 6.67, ratio = 10/6.67 = 1.5
        assertEquals(6.67, result.buckets().get(3).baseline(), 0.1);
        assertEquals(1.5, result.buckets().get(3).ratio(), 0.1);
    }

    @Test
    void ratio_multipleAnomalies_allReported() {
        var buckets = new ArrayList<BucketStats>();
        buckets.add(bucket("10:00", 1000, 0));
        for (int i = 1; i <= 4; i++) {
            buckets.add(bucket("10:0" + i, 1000 + i * 300, 2));
        }
        buckets.add(bucket("10:25", 2500, 100));
        buckets.add(bucket("10:30", 2800, 2));
        buckets.add(bucket("10:35", 3100, 100));

        var result = service.detect(buckets, 3.0, 5, "ERROR_COUNT", "count", "ratio");

        assertTrue(result.anomalies().size() >= 2);
    }

    @Test
    void ratio_preservesSignalTypeInAnomalies() {
        var buckets = List.of(
                bucket("10:00", 1000, 1),
                bucket("10:05", 1300, 1),
                bucket("10:10", 1600, 1),
                bucket("10:15", 1900, 50)
        );

        var result = service.detect(buckets, 3.0, 3, "API_LATENCY", "count", "ratio");

        assertFalse(result.anomalies().isEmpty());
        assertEquals("API_LATENCY", result.anomalies().getFirst().signalType());
    }

    @Test
    void ratio_zeroBaselineAfterWarmup_flagsActivity() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 0),
                bucket("10:10", 1600, 0),
                bucket("10:15", 1900, 10) // baseline = 0, count > 0
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "ratio");

        assertEquals("ANOMALY", result.buckets().get(3).status());
    }

    // ---- Z-score strategy tests ----

    @Test
    void zscore_coldStart_firstThreeBucketsNeverFlagged() {
        var buckets = List.of(
                bucket("10:00", 1000, 100),
                bucket("10:05", 1300, 100),
                bucket("10:10", 1600, 100),
                bucket("10:15", 1900, 1)
        );

        var result = service.detect(buckets, 3.0, 8, "ERROR_COUNT", "count", "zscore");

        assertEquals("normal", result.buckets().get(0).status());
        assertEquals("normal", result.buckets().get(1).status());
        assertEquals("normal", result.buckets().get(2).status());
    }

    @Test
    void zscore_spikeDetected() {
        var buckets = List.of(
                bucket("10:00", 1000, 10),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 10),
                bucket("10:20", 2200, 100) // huge spike, low stddev
        );

        var result = service.detect(buckets, 3.0, 4, "ERROR_COUNT", "count", "zscore");

        assertEquals("ANOMALY", result.buckets().get(4).status());
    }

    @Test
    void zscore_highVariance_noFalseAnomaly() {
        // Noisy signal: values oscillate widely, so a moderate jump shouldn't trigger
        var buckets = List.of(
                bucket("10:00", 1000, 5),
                bucket("10:05", 1300, 20),
                bucket("10:10", 1600, 3),
                bucket("10:15", 1900, 18),
                bucket("10:20", 2200, 25) // not anomalous relative to the noise
        );

        var result = service.detect(buckets, 3.0, 4, "ERROR_COUNT", "count", "zscore");

        // With high variance, 25 shouldn't be 3 stddevs above mean
        assertNotEquals("ANOMALY", result.buckets().get(4).status());
    }

    @Test
    void zscore_lowVariance_detectsSmallJump() {
        // Stable signal: all values are 10, then a jump to 30
        var buckets = List.of(
                bucket("10:00", 1000, 10),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 30) // stddev ≈ 0, mean = 10, jump is huge relatively
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        assertEquals("ANOMALY", result.buckets().get(3).status());
    }

    @Test
    void zscore_zeroStddev_stableValues_staysNormal() {
        var buckets = List.of(
                bucket("10:00", 1000, 5),
                bucket("10:05", 1300, 5),
                bucket("10:10", 1600, 5),
                bucket("10:15", 1900, 5)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        assertEquals("normal", result.buckets().get(3).status());
    }

    @Test
    void zscore_zeroBaselineAfterWarmup_flagsActivity() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 0),
                bucket("10:10", 1600, 0),
                bucket("10:15", 1900, 10)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        assertEquals("ANOMALY", result.buckets().get(3).status());
    }

    @Test
    void zscore_elevatedStatus() {
        // Build a scenario where z-score is between threshold*0.5 and threshold
        // mean=10, stddev≈0 (uniform), jump to 15 → z = threshold (fallback)
        // Instead, use varied values to get moderate z
        var buckets = List.of(
                bucket("10:00", 1000, 8),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 12),
                bucket("10:15", 1900, 10),
                // mean ≈ 10, stddev ≈ 1.63
                // value 14: z = (14-10)/1.63 ≈ 2.45 → above 1.5 (threshold*0.5) but below 3.0
                bucket("10:20", 2200, 14)
        );

        var result = service.detect(buckets, 3.0, 4, "ERROR_COUNT", "count", "zscore");

        assertEquals("elevated", result.buckets().get(4).status());
    }

    @Test
    void zscore_baselineIsRollingMean() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 20),
                bucket("10:15", 1900, 10)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        // Index 3: baseline = mean of [0, 10, 20] = 10.0
        assertEquals(10.0, result.buckets().get(3).baseline(), 0.01);
    }

    // ---- Metric selection tests ----

    @Test
    void metric_p95_usesP95Values() {
        var buckets = List.of(
                bucketWithStats("10:00", 1000, 5, 500, 10, 200, 100),
                bucketWithStats("10:05", 1300, 5, 500, 10, 200, 100),
                bucketWithStats("10:10", 1600, 5, 500, 10, 200, 100),
                bucketWithStats("10:15", 1900, 5, 500, 10, 200, 5000) // p95 spike
        );

        var result = service.detect(buckets, 3.0, 3, "API_LATENCY", "p95", "ratio");

        // p95=5000 vs baseline p95=100 → ratio = 50 → ANOMALY
        assertEquals("ANOMALY", result.buckets().get(3).status());
        assertEquals(5000.0, result.anomalies().getFirst().observedValue(), 0.01);
    }

    @Test
    void metric_max_usesMaxValues() {
        var buckets = List.of(
                bucketWithStats("10:00", 1000, 5, 500, 10, 50, 40),
                bucketWithStats("10:05", 1300, 5, 500, 10, 50, 40),
                bucketWithStats("10:10", 1600, 5, 500, 10, 50, 40),
                bucketWithStats("10:15", 1900, 5, 500, 10, 5000, 40) // max spike
        );

        var result = service.detect(buckets, 3.0, 3, "API_LATENCY", "max", "ratio");

        assertEquals("ANOMALY", result.buckets().get(3).status());
        assertEquals(5000.0, result.anomalies().getFirst().observedValue(), 0.01);
    }

    @Test
    void metric_avg_usesAvgValues() {
        var buckets = List.of(
                bucketWithStats("10:00", 1000, 10, 100, 5, 20, 15), // avg=10
                bucketWithStats("10:05", 1300, 10, 100, 5, 20, 15), // avg=10
                bucketWithStats("10:10", 1600, 10, 100, 5, 20, 15), // avg=10
                bucketWithStats("10:15", 1900, 10, 500, 5, 100, 80) // avg=50, spike
        );

        var result = service.detect(buckets, 3.0, 3, "API_LATENCY", "avg", "ratio");

        assertEquals("ANOMALY", result.buckets().get(3).status());
        assertEquals(50.0, result.anomalies().getFirst().observedValue(), 0.01);
    }

    @Test
    void metric_count_isDefault() {
        var buckets = List.of(
                bucket("10:00", 1000, 1),
                bucket("10:05", 1300, 1),
                bucket("10:10", 1600, 1),
                bucket("10:15", 1900, 50)
        );

        // Using legacy overload (defaults to count + ratio)
        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT");

        assertEquals(50.0, result.anomalies().getFirst().observedValue(), 0.01);
    }

    // ---- Invalid inputs ----

    @Test
    void invalidMetric_throws() {
        var buckets = List.of(bucket("10:00", 1000, 5));
        assertThrows(IllegalArgumentException.class, () ->
                service.detect(buckets, 3.0, 3, "ERROR_COUNT", "invalid", "ratio"));
    }

    @Test
    void invalidMethod_throws() {
        var buckets = List.of(bucket("10:00", 1000, 5));
        assertThrows(IllegalArgumentException.class, () ->
                service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "badmethod"));
    }

    // ---- Both strategies handle empty/null identically ----

    @Test
    void zscore_emptyBuckets_returnsEmptyResult() {
        var result = service.detect(List.of(), 3.0, 8, "ERROR_COUNT", "count", "zscore");
        assertTrue(result.buckets().isEmpty());
        assertTrue(result.anomalies().isEmpty());
    }

    @Test
    void zscore_nullBuckets_returnsEmptyResult() {
        var result = service.detect(null, 3.0, 8, "ERROR_COUNT", "count", "zscore");
        assertTrue(result.buckets().isEmpty());
        assertTrue(result.anomalies().isEmpty());
    }

    // ---- Z-score proportional zero-stddev fallback ----

    @Test
    void zscore_zeroStddev_2xMean_getsThreshold() {
        // Uniform baseline of 10, then jump to 20 (2x mean)
        // Proportional: (20/10 - 1) * 3.0 = 3.0 → exactly at threshold → ANOMALY
        var buckets = List.of(
                bucket("10:00", 1000, 10),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 20)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        assertEquals("ANOMALY", result.buckets().get(3).status());
        assertEquals(3.0, result.buckets().get(3).ratio(), 0.01);
    }

    @Test
    void zscore_zeroStddev_1_5xMean_elevated() {
        // Uniform baseline of 10, then jump to 15 (1.5x mean)
        // Proportional: (15/10 - 1) * 3.0 = 1.5 → exactly at elevated threshold
        var buckets = List.of(
                bucket("10:00", 1000, 10),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 15)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        assertEquals("elevated", result.buckets().get(3).status());
        assertEquals(1.5, result.buckets().get(3).ratio(), 0.01);
    }

    @Test
    void zscore_zeroStddev_belowMean_normal() {
        // Uniform baseline of 10, then drop to 5 — below mean should always be normal
        var buckets = List.of(
                bucket("10:00", 1000, 10),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 5)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        assertEquals("normal", result.buckets().get(3).status());
        assertEquals(0.0, result.buckets().get(3).ratio(), 0.01);
    }

    @Test
    void zscore_zeroStddev_cappedAtThreshold() {
        // Uniform baseline of 10, massive jump to 100 (10x mean)
        // Proportional: (100/10 - 1) * 3.0 = 27.0 → capped at threshold (3.0)
        var buckets = List.of(
                bucket("10:00", 1000, 10),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 100)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT", "count", "zscore");

        assertEquals("ANOMALY", result.buckets().get(3).status());
        assertEquals(3.0, result.buckets().get(3).ratio(), 0.01); // capped
    }

    // ---- Ratio strategy with non-count metrics ----

    @Test
    void ratio_metricP95_rollingMeanUsesP95() {
        var buckets = List.of(
                bucketWithStats("10:00", 1000, 5, 500, 10, 200, 100),
                bucketWithStats("10:05", 1300, 5, 500, 10, 200, 100),
                bucketWithStats("10:10", 1600, 5, 500, 10, 200, 100),
                bucketWithStats("10:15", 1900, 5, 500, 10, 200, 100)
        );

        var result = service.detect(buckets, 3.0, 3, "API_LATENCY", "p95", "ratio");

        // All p95 values are 100, baseline = 100, ratio = 1.0 → normal
        assertEquals("normal", result.buckets().get(3).status());
        assertEquals(100.0, result.buckets().get(3).baseline(), 0.01);
    }

    @Test
    void ratio_metricAvg_rollingMeanUsesAvg() {
        var buckets = List.of(
                bucketWithStats("10:00", 1000, 10, 100, 5, 20, 15), // avg=10
                bucketWithStats("10:05", 1300, 10, 100, 5, 20, 15), // avg=10
                bucketWithStats("10:10", 1600, 10, 100, 5, 20, 15), // avg=10
                bucketWithStats("10:15", 1900, 10, 100, 5, 20, 15)  // avg=10
        );

        var result = service.detect(buckets, 3.0, 3, "API_LATENCY", "avg", "ratio");

        // All avg values are 10, baseline = 10, ratio = 1.0 → normal
        assertEquals("normal", result.buckets().get(3).status());
        assertEquals(10.0, result.buckets().get(3).baseline(), 0.01);
    }
}
