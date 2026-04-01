package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;

public record CriticalIssue(
        String category,
        String severity,
        String pattern,
        int lineNumber,
        LocalDateTime timestamp,
        String message,
        String sourceFile
) {}
