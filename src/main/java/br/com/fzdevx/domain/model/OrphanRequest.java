package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;

public record OrphanRequest(
        String endpoint,
        String thread,
        LocalDateTime timestamp,
        String payload,
        int lineNumber,
        String sourceFile
) {}
