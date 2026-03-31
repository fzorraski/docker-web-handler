package br.com.fzdevx.domain.model;

import java.time.LocalDateTime;
import java.util.Map;

public record CustomFieldMatch(
        int lineNumber,
        LocalDateTime timestamp,
        String thread,
        String sourceFile,
        String fullMessage,
        Map<String, String> groups
) {}
