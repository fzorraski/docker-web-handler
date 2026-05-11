package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;

public record ApiCallPairResponse(
        String endpoint,
        String correlationId,
        String thread,
        LocalDateTime requestTimestamp,
        LocalDateTime responseTimestamp,
        long durationMs,
        long upstreamDurationMs,
        long connectionDelayMs,
        boolean slowConnection,
        String requestPayload,
        String responsePayload,
        int requestLineNumber,
        int responseLineNumber,
        String sourceFile,
        boolean slow,
        boolean requestPayloadTruncated,
        boolean responsePayloadTruncated,
        int requestPayloadSize,
        int responsePayloadSize
) {}
