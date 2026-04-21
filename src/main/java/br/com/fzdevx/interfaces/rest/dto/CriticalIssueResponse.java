package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;

public record CriticalIssueResponse(
        String category,
        String severity,
        String pattern,
        int lineNumber,
        LocalDateTime timestamp,
        String message,
        String sourceFile,
        boolean messageTruncated,
        int messageSize
) {}
