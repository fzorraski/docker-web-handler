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
        String lastRestoredBy,
        String createdBy,
        String tenantId,
        /**
         * Whether the CURRENT caller created this database. Stamped per request in
         * the controller - the cached list this record comes from is shared across
         * users - and deliberately surviving {@link #withoutCreatedBy}: it tells a
         * DATABASE_DELETE_OWN holder which rows they may delete without revealing
         * anyone's identity.
         */
        boolean createdByMe
) {

    /** Copy without actor identities, for callers lacking the AUDIT_VIEW permission. */
    public ManagedDatabaseInfo withoutCreatedBy() {
        return new ManagedDatabaseInfo(name, repository, sizeBytes, activeConnections,
                pgLastActivity, appLastUsedAt, effectiveLastUsedAt, protectedFlag, createdAt,
                description, containerCount, earliestExpiration, scheduledForDeletion,
                lastRestoredFrom, lastRestoredAt, null, null, tenantId, createdByMe);
    }

    /** Copy with the per-caller ownership flag stamped. */
    public ManagedDatabaseInfo withCreatedByMe(boolean mine) {
        return new ManagedDatabaseInfo(name, repository, sizeBytes, activeConnections,
                pgLastActivity, appLastUsedAt, effectiveLastUsedAt, protectedFlag, createdAt,
                description, containerCount, earliestExpiration, scheduledForDeletion,
                lastRestoredFrom, lastRestoredAt, lastRestoredBy, createdBy, tenantId, mine);
    }
}
