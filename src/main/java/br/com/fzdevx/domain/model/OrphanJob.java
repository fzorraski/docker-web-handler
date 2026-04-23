package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;

public record OrphanJob(
        String jobName,
        String triggerName,
        String thread,
        LocalDateTime timestamp,
        int lineNumber,
        String sourceFile
) {}
