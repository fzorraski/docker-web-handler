package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.BucketStats;
import br.com.fzdevx.domain.model.anomaly.Signal;
import br.com.fzdevx.domain.model.anomaly.SignalType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeBucketAggregatorTest {

    private final LocalDateTime start = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
    private final LocalDateTime end = LocalDateTime.of(2025, 6, 15, 10, 30, 0);

    private Signal signal(LocalDateTime ts, long value) {
        return new Signal(SignalType.ERROR_COUNT, value, "msg", ts, "thread", "logger");
    }

    @Test
    void aggregate_createsCorrectNumberOfBuckets() {
        // 30-minute range with 300s (5min) buckets; alignedEnd rounds up so we get 7 buckets
        List<BucketStats> buckets = TimeBucketAggregator.aggregate(List.of(), 300, start, end);

        assertEquals(7, buckets.size());
        buckets.forEach(b -> {
            assertEquals(0, b.count());
            assertEquals("normal", b.status());
        });
    }

    @Test
    void aggregate_countsSignalsInCorrectBuckets() {
        var signals = List.of(
                signal(start.plusMinutes(1), 10),
                signal(start.plusMinutes(2), 20),
                signal(start.plusMinutes(7), 50)
        );

        List<BucketStats> buckets = TimeBucketAggregator.aggregate(signals, 300, start, end);

        // First bucket (10:00-10:05): 2 signals
        assertEquals(2, buckets.get(0).count());
        assertEquals(30, buckets.get(0).sum());
        assertEquals(10, buckets.get(0).min());
        assertEquals(20, buckets.get(0).max());

        // Second bucket (10:05-10:10): 1 signal
        assertEquals(1, buckets.get(1).count());
        assertEquals(50, buckets.get(1).sum());
    }

    @Test
    void aggregate_fillsGapsWithEmptyBuckets() {
        var signals = List.of(
                signal(start, 1),
                signal(start.plusMinutes(25), 1)
        );

        List<BucketStats> buckets = TimeBucketAggregator.aggregate(signals, 300, start, end);

        long nonEmpty = buckets.stream().filter(b -> b.count() > 0).count();
        long empty = buckets.stream().filter(b -> b.count() == 0).count();
        assertEquals(2, nonEmpty);
        assertTrue(empty >= 3);
    }

    @Test
    void aggregate_nullSignals_returnsEmptyBuckets() {
        List<BucketStats> buckets = TimeBucketAggregator.aggregate(null, 300, start, end);

        assertFalse(buckets.isEmpty());
        buckets.forEach(b -> assertEquals(0, b.count()));
    }

    @Test
    void aggregate_nullStart_returnsEmpty() {
        List<BucketStats> result = TimeBucketAggregator.aggregate(List.of(), 300, null, end);
        assertTrue(result.isEmpty());
    }

    @Test
    void aggregate_nullEnd_returnsEmpty() {
        List<BucketStats> result = TimeBucketAggregator.aggregate(List.of(), 300, start, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void aggregate_zeroBucketSize_returnsEmpty() {
        List<BucketStats> result = TimeBucketAggregator.aggregate(List.of(), 0, start, end);
        assertTrue(result.isEmpty());
    }

    @Test
    void aggregate_signalWithNullTimestamp_isSkipped() {
        var signals = List.of(
                new Signal(SignalType.ERROR_COUNT, 1L, "msg", null, "t", "l"),
                signal(start.plusMinutes(1), 5)
        );

        List<BucketStats> buckets = TimeBucketAggregator.aggregate(signals, 300, start, end);

        long total = buckets.stream().mapToInt(BucketStats::count).sum();
        assertEquals(1, total);
    }

    @Test
    void aggregate_signalWithNullNumericValue_treatedAsOne() {
        var signals = List.of(
                new Signal(SignalType.NPE, null, "NullPointerException", start.plusMinutes(1), "t", "l")
        );

        List<BucketStats> buckets = TimeBucketAggregator.aggregate(signals, 300, start, end);

        assertEquals(1, buckets.get(0).count());
        assertEquals(1, buckets.get(0).sum());
    }

    @Test
    void aggregate_computesP95Correctly() {
        // Put 20 signals with values 1..20 in same bucket
        var signals = new java.util.ArrayList<Signal>();
        for (int i = 1; i <= 20; i++) {
            signals.add(signal(start.plusSeconds(i), i));
        }

        List<BucketStats> buckets = TimeBucketAggregator.aggregate(signals, 300, start, end);

        assertEquals(20, buckets.get(0).count());
        assertEquals(19, buckets.get(0).p95()); // ceil(20*0.95)-1 = index 18 = value 19
    }

    @Test
    void aggregate_clampsSignalBeforeRange() {
        var signals = List.of(
                signal(start.minusMinutes(10), 99)
        );

        List<BucketStats> buckets = TimeBucketAggregator.aggregate(signals, 300, start, end);

        // Signal before range gets clamped to first bucket
        assertEquals(1, buckets.get(0).count());
    }

    @Test
    void aggregate_clampsSignalAfterRange() {
        var signals = List.of(
                signal(end.plusMinutes(10), 99)
        );

        List<BucketStats> buckets = TimeBucketAggregator.aggregate(signals, 300, start, end);

        // Signal after range gets clamped to last bucket
        BucketStats last = buckets.getLast();
        assertEquals(1, last.count());
    }
}
