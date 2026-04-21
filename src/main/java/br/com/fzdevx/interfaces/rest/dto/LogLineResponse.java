package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;

public record LogLineResponse(
        int lineNumber,
        LocalDateTime timestamp,
        String level,
        String logger,
        String thread,
        String message,
        String sourceFile,
        boolean messageTruncated,
        int messageSize
) {}
