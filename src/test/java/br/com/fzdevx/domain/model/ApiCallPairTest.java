package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ApiCallPairTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 1, 10, 0, 0);

    private ApiCallPair pair(long durationMs, long upstreamMs) {
        return new ApiCallPair("/api/test", null, "t-1", NOW, NOW.plusSeconds(1),
                durationMs, upstreamMs, false, null, null, 1, 2, "test.log", false);
    }

    // ---- connectionDelayMs() ----

    @Test
    void connectionDelayMs_noUpstream_returnsNegativeOne() {
        assertEquals(-1, pair(500, -1).connectionDelayMs());
    }

    @Test
    void connectionDelayMs_withUpstream_returnsPositiveDifference() {
        assertEquals(50, pair(200, 150).connectionDelayMs());
    }

    @Test
    void connectionDelayMs_upstreamEqualsTotal_returnsZero() {
        assertEquals(0, pair(100, 100).connectionDelayMs());
    }

    @Test
    void connectionDelayMs_upstreamExceedsTotal_returnsZero() {
        // Rare edge case in nginx logs — clamped to 0
        assertEquals(0, pair(100, 150).connectionDelayMs());
    }

    @Test
    void connectionDelayMs_upstreamZero_returnsTotalDuration() {
        assertEquals(200, pair(200, 0).connectionDelayMs());
    }

    @Test
    void connectionDelayMs_bothZero_returnsZero() {
        assertEquals(0, pair(0, 0).connectionDelayMs());
    }
}
