package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record ExceptionLocationSummary(
        String exceptionType,
        String origin,
        String originClass,
        String method,
        String sourceFile,
        int sourceLine,
        int count,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        List<ExceptionOccurrence> occurrences
) {}
