package br.com.fzdevx.application.dto;

import java.time.Instant;


public record ManagedDatabaseInfo(
        String name,
        String repository,
        long sizeBytes,
        int activeConnections,
        Instant pgLastActivity,
        Instant appLastUsedAt,
        Instant effectiveLastUsedAt,
        boolean protectedFlag,
        Instant createdAt,
        String description,
        int containerCount,
        Instant earliestExpiration,
        boolean scheduledForDeletion,
        String lastRestoredFrom,
        Instant lastRestoredAt
) {}
