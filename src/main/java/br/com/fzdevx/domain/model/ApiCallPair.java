package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;

public record ApiCallPair(
        String endpoint,
        String correlationId,
        String thread,
        LocalDateTime requestTimestamp,
        LocalDateTime responseTimestamp,
        long durationMs,
        String requestPayload,
        String responsePayload,
        int requestLineNumber,
        int responseLineNumber,
        String sourceFile,
        boolean slow
) {}
