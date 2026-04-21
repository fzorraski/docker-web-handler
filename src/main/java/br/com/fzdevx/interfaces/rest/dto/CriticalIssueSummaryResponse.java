package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CriticalIssueSummaryResponse(
        String category,
        String severity,
        int count,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        List<CriticalIssueResponse> issues,
        List<CriticalBurstResponse> bursts
) {}
