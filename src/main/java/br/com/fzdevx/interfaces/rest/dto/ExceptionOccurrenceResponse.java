package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ExceptionOccurrenceResponse(
        String exceptionType,
        String originClass,
        String method,
        String sourceFile,
        int sourceLine,
        String message,
        LocalDateTime timestamp,
        int logLineNumber,
        String logSourceFile,
        List<String> stackTrace,
        boolean messageTruncated,
        int messageSize
) {}
