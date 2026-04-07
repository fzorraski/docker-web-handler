package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.BucketStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricExtractorTest {

    private BucketStats bucket(int count, long sum, long min, long max, long p95) {
        return new BucketStats("10:00", 1000, count, sum, min, max, p95, "normal", 0.0, 0.0);
    }

    @Test
    void extract_count() {
        assertEquals(42.0, MetricExtractor.extract(bucket(42, 100, 1, 50, 40), "count"));
    }

    @Test
    void extract_p95() {
        assertEquals(95.0, MetricExtractor.extract(bucket(10, 500, 5, 100, 95), "p95"));
    }

    @Test
    void extract_max() {
        assertEquals(200.0, MetricExtractor.extract(bucket(10, 500, 5, 200, 95), "max"));
    }

    @Test
    void extract_avg_nonZeroCount() {
        // sum=500, count=10 → avg=50.0
        assertEquals(50.0, MetricExtractor.extract(bucket(10, 500, 5, 200, 95), "avg"));
    }

    @Test
    void extract_avg_zeroCount() {
        assertEquals(0.0, MetricExtractor.extract(bucket(0, 0, 0, 0, 0), "avg"));
    }

    @Test
    void extract_invalidMetric_throws() {
        var b = bucket(1, 1, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> MetricExtractor.extract(b, "invalid"));
    }

    @Test
    void validMetrics_containsAllFour() {
        assertTrue(MetricExtractor.VALID_METRICS.contains("count"));
        assertTrue(MetricExtractor.VALID_METRICS.contains("p95"));
        assertTrue(MetricExtractor.VALID_METRICS.contains("max"));
        assertTrue(MetricExtractor.VALID_METRICS.contains("avg"));
        assertEquals(4, MetricExtractor.VALID_METRICS.size());
    }
}
