package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record CriticalBurst(
        String category,
        String severity,
        LocalDateTime burstStart,
        LocalDateTime burstEnd,
        int issueCount,
        List<CriticalIssue> issues
) {}
