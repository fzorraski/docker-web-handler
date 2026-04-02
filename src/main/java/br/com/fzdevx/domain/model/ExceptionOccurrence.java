package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record ExceptionOccurrence(
        String exceptionType,
        String originClass,
        String method,
        String sourceFile,
        int sourceLine,
        String message,
        LocalDateTime timestamp,
        int logLineNumber,
        String logSourceFile,
        List<String> stackTrace
) {}
