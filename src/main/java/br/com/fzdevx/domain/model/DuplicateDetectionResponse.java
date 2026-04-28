package br.com.fzdevx.domain.model;

import java.util.List;

public record DuplicateDetectionResponse(
        String matchBy,
        int timeWindowSeconds,
        int minOccurrences,
        int totalGroups,
        int totalDuplicateCalls,
        String mostDuplicatedEndpoint,
        int mostDuplicatedCount,
        long avgTimeBetweenDuplicatesMs,
        List<DuplicateGroup> groups
) {}
