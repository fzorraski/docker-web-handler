package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;

public record ApiCallPair(
        String endpoint,
        String correlationId,
        String thread,
        LocalDateTime requestTimestamp,
        LocalDateTime responseTimestamp,
        long durationMs,
        long upstreamDurationMs,
        boolean slowConnection,
        String requestPayload,
        String responsePayload,
        int requestLineNumber,
        int responseLineNumber,
        String sourceFile,
        boolean slow
) {
    /**
     * Connection delay in milliseconds (durationMs - upstreamDurationMs).
     * Returns -1 when upstream duration is not available.
     * Returns 0 when upstream exceeds total (rare edge case in nginx logs).
     */
    public long connectionDelayMs() {
        return upstreamDurationMs >= 0 ? Math.max(0, durationMs - upstreamDurationMs) : -1;
    }
}
