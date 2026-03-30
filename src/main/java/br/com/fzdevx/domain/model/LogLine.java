package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;

public record LogLine(
        int lineNumber,
        LocalDateTime timestamp,
        String level,
        String logger,
        String thread,
        String message,
        String sourceFile
) {}
