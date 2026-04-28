package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record DuplicateGroup(
        String endpoint,
        String payloadPreview,
        int occurrenceCount,
        LocalDateTime firstOccurrence,
        LocalDateTime lastOccurrence,
        long timeSpanMs,
        long avgTimeBetweenMs,
        List<DuplicateCall> calls
) {
    public record DuplicateCall(
            String correlationId,
            String thread,
            LocalDateTime requestTimestamp,
            long durationMs,
            int requestLineNumber,
            int responseLineNumber,
            String sourceFile,
            boolean slow
    ) {}
}
