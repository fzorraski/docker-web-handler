package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;

public record OrphanRequestResponse(
        String endpoint,
        String thread,
        LocalDateTime timestamp,
        String payload,
        int lineNumber,
        String sourceFile,
        boolean payloadTruncated,
        int payloadSize
) {}
