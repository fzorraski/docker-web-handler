package br.com.fzdevx.interfaces.rest.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record CustomFieldMatchResponse(
        int lineNumber,
        LocalDateTime timestamp,
        String thread,
        String sourceFile,
        String fullMessage,
        Map<String, String> groups,
        boolean fullMessageTruncated,
        int fullMessageSize
) {}
