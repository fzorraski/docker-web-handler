package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.CorrelatedAnomaly;
import br.com.fzdevx.domain.shared.CorrelationDetector;
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
        assertTrue(CorrelationDetector.detect(null, 6).isEmpty());
    }

    @Test
    void detect_singleType_returnsEmpty() {
        var map = Map.of("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        assertTrue(CorrelationDetector.detect(map, 6).isEmpty());
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
    void detect_twoTypesInAdjacentBuckets_withinWindow() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 4.0)));
        map.put("SLOW_QUERY", List.of(anomaly("SLOW_QUERY", "10:05", 3.5)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertEquals("10:00", result.getFirst().windowStart());
        assertEquals("10:05", result.getFirst().windowEnd());
    }

    @Test
    void detect_emptyAnomalyLists_returnsEmpty() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of());
        map.put("NPE", List.of());

        assertTrue(CorrelationDetector.detect(map, 6).isEmpty());
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
    }

    @Test
    void detect_multipleCorrelations_sortedByScoreDescending() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(
                anomaly("ERROR_COUNT", "10:00", 3.0),
                anomaly("ERROR_COUNT", "10:10", 2.0),
                anomaly("ERROR_COUNT", "10:15", 2.0),
                anomaly("ERROR_COUNT", "10:20", 10.0)
        ));
        map.put("NPE", List.of(
                anomaly("NPE", "10:05", 2.0),
                anomaly("NPE", "10:25", 9.0)
        ));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 1);

        assertFalse(result.isEmpty());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).score() >= result.get(i).score());
        }
    }

    // ---- Severity classification ----

    @Test
    void detect_severity_low() {
        // Low ratios in separate buckets (no proximity bonus) → low score
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 1.5)));
        map.put("NPE", List.of(anomaly("NPE", "10:05", 1.5)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().score() < 5.0);
        assertEquals("low", result.getFirst().severity());
    }

    @Test
    void detect_severity_critical_highScore() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 10.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:00", 8.0)));
        map.put("OOM", List.of(anomaly("OOM", "10:00", 12.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertEquals("critical", result.getFirst().severity());
        assertTrue(result.getFirst().score() >= 20.0);
    }

    // ---- Causal chain ----

    @Test
    void detect_causalChain_orderedByCausalPriority() {
        // OOM and ERROR_COUNT in same bucket — OOM should come first (higher causal priority)
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("OOM", List.of(anomaly("OOM", "10:00", 7.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        List<String> chain = result.getFirst().causalChain();
        assertEquals(2, chain.size());
        assertEquals("OOM", chain.get(0)); // root cause
        assertEquals("ERROR_COUNT", chain.get(1)); // effect
    }

    @Test
    void detect_causalChain_earlierBucketComesFirst() {
        // ERROR_COUNT at 10:00, POOL_EXHAUSTION at 10:05
        // Even though POOL_EXHAUSTION has higher causal priority, ERROR_COUNT is earlier in time
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("POOL_EXHAUSTION", List.of(anomaly("POOL_EXHAUSTION", "10:05", 7.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        List<String> chain = result.getFirst().causalChain();
        // ERROR_COUNT is at 10:00 (earlier), POOL_EXHAUSTION at 10:05
        assertEquals("ERROR_COUNT", chain.get(0));
        assertEquals("POOL_EXHAUSTION", chain.get(1));
    }

    @Test
    void detect_causalChain_sameBucket_usesInfraBeforeApp() {
        // POOL_EXHAUSTION and SQL_EXCEPTION in same bucket
        // POOL_EXHAUSTION has higher causal priority → comes first
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("SQL_EXCEPTION", List.of(anomaly("SQL_EXCEPTION", "10:00", 5.0)));
        map.put("POOL_EXHAUSTION", List.of(anomaly("POOL_EXHAUSTION", "10:00", 7.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        List<String> chain = result.getFirst().causalChain();
        assertEquals("POOL_EXHAUSTION", chain.get(0));
        assertEquals("SQL_EXCEPTION", chain.get(1));
    }

    // ---- Temporal proximity scoring ----

    @Test
    void detect_sameBucket_higherScoreThanDistant() {
        // Same types, same ratios — but one is co-located, other is spread
        var colocated = new LinkedHashMap<String, List<AnomalyResult>>();
        colocated.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        colocated.put("NPE", List.of(anomaly("NPE", "10:00", 5.0)));

        var spread = new LinkedHashMap<String, List<AnomalyResult>>();
        spread.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        spread.put("NPE", List.of(anomaly("NPE", "10:05", 5.0)));

        var colocatedResult = CorrelationDetector.detect(colocated, 6);
        var spreadResult = CorrelationDetector.detect(spread, 6);

        assertTrue(colocatedResult.getFirst().score() > spreadResult.getFirst().score());
    }

    // ---- Window grouping ----

    @Test
    void detect_windowGroupsAnomaliesFromDifferentTypes() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("GC_PAUSE", List.of(anomaly("GC_PAUSE", "10:05", 4.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 2);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().signalTypes().contains("ERROR_COUNT"));
        assertTrue(result.getFirst().signalTypes().contains("GC_PAUSE"));
    }

    // ---- Severity classification: all levels ----

    @Test
    void detect_severity_medium() {
        // score needs to be >= 5.0 and < 10.0
        // 2 types × avg ratio 3.0 × proximity 1.5 = 9.0 → medium (but close)
        // Use spread anomalies to reduce proximity bonus
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 3.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:05", 3.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().score() >= 5.0);
        assertTrue(result.getFirst().score() < 10.0);
        assertEquals("medium", result.getFirst().severity());
    }

    @Test
    void detect_severity_high() {
        // score needs to be >= 10.0 and < 20.0
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 5.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:05", 5.0)));

        List<CorrelatedAnomaly> result = CorrelationDetector.detect(map, 6);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().score() >= 10.0);
        assertTrue(result.getFirst().score() < 20.0);
        assertEquals("high", result.getFirst().severity());
    }

    // ---- Proximity scoring formula verification ----

    @Test
    void detect_proximityBonus_allInStartBucket_gets1_5x() {
        // 2 anomalies, both in start bucket → inStartBucket/total = 1.0 → factor = 1.5
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 4.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:00", 4.0)));

        var result = CorrelationDetector.detect(map, 6);

        // score = 2 types × avgRatio(4.0) × 1.5 = 12.0
        assertEquals(12.0, result.getFirst().score(), 0.01);
    }

    @Test
    void detect_proximityBonus_halfInStartBucket_gets1_25x() {
        // 2 anomalies, 1 in start bucket → inStartBucket/total = 0.5 → factor = 1.25
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 4.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:05", 4.0)));

        var result = CorrelationDetector.detect(map, 6);

        // score = 2 types × avgRatio(4.0) × 1.25 = 10.0
        assertEquals(10.0, result.getFirst().score(), 0.01);
    }

    // ---- Causal chain edge cases ----

    @Test
    void detect_causalChain_fourTypes_orderedCorrectly() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("ERROR_COUNT", List.of(anomaly("ERROR_COUNT", "10:00", 3.0)));
        map.put("SQL_EXCEPTION", List.of(anomaly("SQL_EXCEPTION", "10:00", 3.0)));
        map.put("POOL_EXHAUSTION", List.of(anomaly("POOL_EXHAUSTION", "10:00", 3.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:00", 3.0)));

        var result = CorrelationDetector.detect(map, 6);

        List<String> chain = result.getFirst().causalChain();
        assertEquals(4, chain.size());
        // All same bucket → ordered by causal priority
        assertEquals("POOL_EXHAUSTION", chain.get(0)); // infrastructure root cause
        assertEquals("SQL_EXCEPTION", chain.get(1));
        assertEquals("NPE", chain.get(2));
        assertEquals("ERROR_COUNT", chain.get(3)); // symptom
    }

    @Test
    void detect_causalChain_unknownType_placedLast() {
        var map = new LinkedHashMap<String, List<AnomalyResult>>();
        map.put("CUSTOM_SIGNAL", List.of(anomaly("CUSTOM_SIGNAL", "10:00", 3.0)));
        map.put("NPE", List.of(anomaly("NPE", "10:00", 3.0)));

        var result = CorrelationDetector.detect(map, 6);

        List<String> chain = result.getFirst().causalChain();
        assertEquals("NPE", chain.get(0)); // known causal priority
        assertEquals("CUSTOM_SIGNAL", chain.get(1)); // unknown → placed after known
    }
}
