package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ExceptionLocationSummaryResponse(
        String exceptionType,
        String origin,
        String originClass,
        String method,
        String sourceFile,
        int sourceLine,
        int count,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        List<ExceptionOccurrenceResponse> occurrences
) {}
