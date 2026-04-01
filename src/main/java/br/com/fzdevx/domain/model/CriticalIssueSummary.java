package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record CriticalIssueSummary(
        String category,
        String severity,
        int count,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        List<CriticalIssue> issues,
        List<CriticalBurst> bursts
) {}
