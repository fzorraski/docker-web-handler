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

    @Test
    void detect_uniformBuckets_noAnomaliesAfterBaseline() {
        // First bucket always gets flagged if count > 0 (zero baseline → ratio = threshold).
        // Subsequent uniform buckets should be "normal" since ratio = 1.0.
        var buckets = List.of(
                bucket("10:00", 1000, 5),
                bucket("10:05", 1300, 5),
                bucket("10:10", 1600, 5),
                bucket("10:15", 1900, 5)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT");

        assertEquals(4, result.buckets().size());
        // First bucket: zero baseline, count>0 → ANOMALY
        assertEquals("ANOMALY", result.buckets().get(0).status());
        // Remaining buckets: baseline = 5, count = 5, ratio = 1.0 → normal
        for (int i = 1; i < result.buckets().size(); i++) {
            assertEquals("normal", result.buckets().get(i).status());
        }
    }

    @Test
    void detect_spikeDetectedAsAnomaly() {
        // Start with zero-count bucket to avoid first-bucket anomaly, then a spike
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 2),
                bucket("10:10", 1600, 2),
                bucket("10:15", 1900, 100) // Huge spike
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT");

        assertEquals("ANOMALY", result.buckets().get(3).status());
        assertTrue(result.anomalies().stream()
                .anyMatch(a -> "10:15".equals(a.bucketLabel()) && "ERROR_COUNT".equals(a.signalType())));
    }

    @Test
    void detect_elevatedStatus_belowThresholdAbove1_5() {
        // baseline of 10, spike to 20 → ratio = 2.0, between 1.5 and threshold 3.0
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 10),
                bucket("10:20", 2200, 20)  // ratio = 20/10 = 2.0
        );

        var result = service.detect(buckets, 3.0, 4, "ERROR_COUNT");

        assertEquals("elevated", result.buckets().get(4).status());
        assertTrue(result.anomalies().stream()
                .noneMatch(a -> "10:20".equals(a.bucketLabel())));
    }

    @Test
    void detect_firstBucket_zeroBaseline_flagsIfActive() {
        var buckets = List.of(
                bucket("10:00", 1000, 5),
                bucket("10:05", 1300, 1)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT");

        // First bucket has zero baseline, current > 0 → ratio = threshold → ANOMALY
        assertEquals("ANOMALY", result.buckets().get(0).status());
    }

    @Test
    void detect_firstBucket_zeroBaselineZeroCount_normal() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 1)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT");

        assertEquals("normal", result.buckets().get(0).status());
    }

    @Test
    void detect_baselineWindowLimitedToAvailablePrior() {
        // Window=8 but only 2 prior non-zero buckets exist
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 1),
                bucket("10:10", 1600, 1),
                bucket("10:15", 1900, 50) // spike
        );

        var result = service.detect(buckets, 3.0, 8, "ERROR_COUNT");

        assertEquals("ANOMALY", result.buckets().get(3).status());
    }

    @Test
    void detect_setsBaselineAndRatioOnBuckets() {
        var buckets = List.of(
                bucket("10:00", 1000, 0),
                bucket("10:05", 1300, 10),
                bucket("10:10", 1600, 10),
                bucket("10:15", 1900, 10)
        );

        var result = service.detect(buckets, 3.0, 3, "ERROR_COUNT");

        // Third bucket (index 2): baseline = avg of [0, 10] = 5.0, ratio = 10/5 = 2.0
        assertEquals(5.0, result.buckets().get(2).baseline(), 0.01);
        assertEquals(2.0, result.buckets().get(2).ratio(), 0.01);
    }

    @Test
    void detect_multipleAnomalies_allReported() {
        var buckets = new ArrayList<BucketStats>();
        buckets.add(bucket("10:00", 1000, 0));
        for (int i = 1; i <= 4; i++) {
            buckets.add(bucket("10:0" + i, 1000 + i * 300, 2));
        }
        buckets.add(bucket("10:25", 2500, 100)); // spike 1
        buckets.add(bucket("10:30", 2800, 2));
        buckets.add(bucket("10:35", 3100, 100)); // spike 2

        var result = service.detect(buckets, 3.0, 5, "ERROR_COUNT");

        assertTrue(result.anomalies().size() >= 2);
    }

    @Test
    void detect_preservesSignalTypeInAnomalies() {
        var buckets = List.of(
                bucket("10:00", 1000, 1),
                bucket("10:05", 1300, 1),
                bucket("10:10", 1600, 50)
        );

        var result = service.detect(buckets, 3.0, 3, "API_LATENCY");

        assertFalse(result.anomalies().isEmpty());
        assertEquals("API_LATENCY", result.anomalies().getFirst().signalType());
    }
}
