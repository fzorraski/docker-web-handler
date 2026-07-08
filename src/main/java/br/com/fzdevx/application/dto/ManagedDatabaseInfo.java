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
        Instant lastRestoredAt,
        String createdBy
) {

    /** Copy without the creator, for callers lacking the AUDIT_VIEW permission. */
    public ManagedDatabaseInfo withoutCreatedBy() {
        return new ManagedDatabaseInfo(name, repository, sizeBytes, activeConnections,
                pgLastActivity, appLastUsedAt, effectiveLastUsedAt, protectedFlag, createdAt,
                description, containerCount, earliestExpiration, scheduledForDeletion,
                lastRestoredFrom, lastRestoredAt, null);
    }
}
