package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record RepeatedFailure(
        String entityId,
        String reason,
        int occurrences,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        List<FailureDetail> details
) {

    public record FailureDetail(
            LocalDateTime timestamp,
            int lineNumber,
            String message,
            String sourceFile
    ) {}
}
