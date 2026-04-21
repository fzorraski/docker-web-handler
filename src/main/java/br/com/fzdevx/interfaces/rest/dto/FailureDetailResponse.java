package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;

public record FailureDetailResponse(
        LocalDateTime timestamp,
        int lineNumber,
        String message,
        String sourceFile,
        boolean messageTruncated,
        int messageSize
) {}
