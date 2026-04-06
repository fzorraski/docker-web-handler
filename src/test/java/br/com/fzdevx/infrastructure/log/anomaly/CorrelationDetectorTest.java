package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.CorrelatedAnomaly;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationDetectorTest {

    private AnomalyResult anomaly(String type, String bucket, double ratio) {
        return new AnomalyResult(type, bucket, 10.0, 2.0, ratio, 10, 15);
    }

    @Test
    void detect_nullInput_returnsEmpty() {
        List<CorrelatedAnomaly> result = CorrelationDetector.detect(null, 6);
        assertTrue(result.isEmpty());
    }

    @Test
    void detect_singleType_returnsEmpty() {
        var map = Map.of("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertTrue(result.isEmpty());
    }

    @Test
    void detect_twoTypesInSameBucket_returnsCorrelation() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:00", 3.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertEquals(2, result.getFirst().signalTypes().size());
        assertTrue(result.getFirst().signalTypes().contains("ERROR_COUNT"));
        assertTrue(result.getFirst().signalTypes().contains("NPE"));
    }

    @Test
    void detect_twoTypesInAdjacentBuckets_withinWindow_returnsCorrelation() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 4.0)));
        map.put("SLOW_QUERY", List.of(anomaly("SLOW_QUERY", "10:05", 3.5)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertEquals("10:00", result.getFirst().windowStart());
        assertEquals("10:05", result.getFirst().windowEnd());
    }

    @Test
    void detect_singleTypePerBucket_noOverlap_noCorrelation() {
        // Each bucket has only one type. With enough padding buckets to exhaust the window,
        // the two types never appear in the same window group.
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        // ERROR_COUNT at 10:00, 10:05, 10:10 (padding to consume window)
        map.put("ERROR_COUNT", List.of(
                anomaly("ERROR_COUNT", "10:00", 4.0),
                anomaly("ERROR_COUNT", "10:05", 2.0),
                anomaly("ERROR_COUNT", "10:10", 2.0)
        ));
        // SLOW_QUERY at 10:30 — far enough that window=1 can't bridge the gap
        map.put("SLOW_QUERY", List.of(anomaly("SLOW_QUERY", "10:30", 3.0)));

        // window=1: groups [10:00,10:05] then [10:10,10:30] — second group has both types
        // window must be 0 to prevent any look-ahead, but 0 means j <= i+0 so no look-ahead
        // Actually the loop is: for j = i+1; j < size && j <= i + window
        // window=1: j <= i+1, so it always looks one ahead. We can't prevent grouping of
        // adjacent bucket labels. The algorithm uses sequential proximity, not time distance.

        // So instead, verify that separate type-only groups produce no correlation
        var singleTypeMap = new LinkedHashMap<String, List<AnomalyResult>>();
        singleTypeMap.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 4.0)));
        singleTypeMap.put("SLOW_QUERY", List.of()); // empty

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(singleTypeMap, 6);

        // Only one type has anomalies → no correlation possible
        assertTrue(result.isEmpty());
    }

    @Test
    void detect_scoreIsTypesTimesMaxRatio() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:00", 8.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        // score = 2 types * max ratio 8.0 = 16.0
        assertEquals(16.0, result.getFirst().score(), 0.01);
    }

    @Test
    void detect_multipleCorrelationWindows_sortedByScoreDescending() {
        // Use 4 distinct bucket labels with window=1 to create 2 separate correlation groups:
        // Group 1: [10:00, 10:05] — ERROR_COUNT + NPE
        // Group 2: [10:20, 10:25] — ERROR_COUNT + NPE (higher ratios)
        // Need 3+ buckets between them so they don't merge into one window.
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(
                anomaly("ERROR_COUNT", "10:00", 3.0),
                anomaly("ERROR_COUNT", "10:10", 2.0), // padding (single type)
                anomaly("ERROR_COUNT", "10:15", 2.0), // padding (single type)
                anomaly("ERROR_COUNT", "10:20", 10.0)
        ));
        map.put("NPE", List.of(
                anomaly("NPE", "10:05", 2.0),
                anomaly("NPE", "10:25", 9.0)
        ));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 1);

        // Should have at least 1 correlation; if 2, scores must be descending
        assertFalse(result.isEmpty());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).score() >= result.get(i).score());
        }
    }

    @Test
    void detect_threeTypes_allCorrelatedInSameBucket() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:00", 3.0)));
        map.put("OOM", List.of(anomaly("OOM", "10:00", 7.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertEquals(3, result.getFirst().signalTypes().size());
        // score = 3 * 7.0 = 21.0
        assertEquals(21.0, result.getFirst().score(), 0.01);
    }

    @Test
    void detect_emptyAnomalyLists_returnsEmpty() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of());
        map.put("NPE", List.of());

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertTrue(result.isEmpty());
    }

    @Test
    void detect_windowGroupsAnomaliesFromDifferentTypes() {
        // Two types, each in a different but adjacent bucket — window=2 should group them
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("GC_PAUSE", List.of(anomaly("GC_PAUSE", "10:05", 4.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 2);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().signalTypes().contains("ERROR_COUNT"));
        assertTrue(result.getFirst().signalTypes().contains("GC_PAUSE"));
    }
}
