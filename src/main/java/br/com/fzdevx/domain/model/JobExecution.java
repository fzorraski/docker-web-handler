package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;

public record JobExecution(
        String jobName,
        String triggerName,
        String thread,
        LocalDateTime startTimestamp,
        LocalDateTime endTimestamp,
        long durationMs,
        String result,
        int startLineNumber,
        int endLineNumber,
        String sourceFile
) {}
