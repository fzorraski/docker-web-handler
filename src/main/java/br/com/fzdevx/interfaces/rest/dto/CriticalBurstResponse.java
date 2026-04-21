package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CriticalBurstResponse(
        String category,
        String severity,
        LocalDateTime burstStart,
        LocalDateTime burstEnd,
        int issueCount,
        List<CriticalIssueResponse> issues
) {}
