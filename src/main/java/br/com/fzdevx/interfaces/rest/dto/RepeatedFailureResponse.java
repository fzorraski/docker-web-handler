package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RepeatedFailureResponse(
        String entityId,
        String reason,
        int occurrences,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        List<FailureDetailResponse> details
) {}
