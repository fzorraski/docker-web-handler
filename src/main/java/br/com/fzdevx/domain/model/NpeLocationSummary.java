package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record NpeLocationSummary(
        String origin,
        String originClass,
        String method,
        String sourceFile,
        int sourceLine,
        int count,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        List<NpeOccurrence> occurrences
) {}
