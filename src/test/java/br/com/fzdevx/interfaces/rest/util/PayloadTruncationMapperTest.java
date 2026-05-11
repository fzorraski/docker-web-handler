package br.com.fzdevx.interfaces.rest.util;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.interfaces.rest.dto.ApiCallPairResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class PayloadTruncationMapperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 1, 10, 0, 0);

    // ---- Connection delay fields mapping ----

    @Test
    void toResponse_mapsConnectionDelayFields_withUpstream() {
        ApiCallPair pair = new ApiCallPair("/api/test", null, "t-1", NOW, NOW.plusSeconds(1),
                200, 150, true, "req", "resp", 1, 2, "test.log", false);

        ApiCallPairResponse response = PayloadTruncationMapper.toResponse(pair, 1000);

        assertEquals(150, response.upstreamDurationMs());
        assertEquals(50, response.connectionDelayMs());   // 200 - 150
        assertTrue(response.slowConnection());
    }

    @Test
    void toResponse_mapsConnectionDelayFields_withoutUpstream() {
        ApiCallPair pair = new ApiCallPair("/api/test", null, "t-1", NOW, NOW.plusSeconds(1),
                200, -1, false, "req", "resp", 1, 2, "test.log", false);

        ApiCallPairResponse response = PayloadTruncationMapper.toResponse(pair, 1000);

        assertEquals(-1, response.upstreamDurationMs());
        assertEquals(-1, response.connectionDelayMs());
        assertFalse(response.slowConnection());
    }

    @Test
    void toResponse_connectionDelayFieldsSurviveTruncation() {
        // Payloads that get truncated should not affect connection delay fields
        String longPayload = "x".repeat(2000);
        ApiCallPair pair = new ApiCallPair("/api/test", null, "t-1", NOW, NOW.plusSeconds(1),
                500, 300, true, longPayload, longPayload, 1, 2, "test.log", false);

        ApiCallPairResponse response = PayloadTruncationMapper.toResponse(pair, 100);

        // Truncation happened
        assertTrue(response.requestPayloadTruncated());
        assertTrue(response.responsePayloadTruncated());

        // But connection delay fields are intact
        assertEquals(300, response.upstreamDurationMs());
        assertEquals(200, response.connectionDelayMs()); // 500 - 300
        assertTrue(response.slowConnection());
    }
}
