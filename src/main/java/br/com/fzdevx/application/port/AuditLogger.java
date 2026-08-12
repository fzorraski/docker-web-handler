package br.com.fzdevx.application.port;

/**
 * Records who did what for security-sensitive operations (container create/remove,
 * database restore/delete, user and role management, settings changes, login).
 * Implementations resolve the acting user from the current request when possible.
 */
public interface AuditLogger {

    /** Records an action performed by the current request's user. */
    void log(String action, String target, String detail);

    /** Records an action with an explicit actor, for flows without a request identity (e.g. login). */
    void logAs(String actor, String action, String target, String detail);

    /**
     * Deletes entries older than the cutoff (retention job).
     * Returns the number of entries removed.
     */
    int removeEntriesOlderThan(java.time.Instant cutoff);

    /** Browses the trail, newest first, for the audit screen (AUDIT_LOG_VIEW). */
    br.com.fzdevx.application.dto.AuditSearchResult search(
            br.com.fzdevx.application.dto.AuditSearchCriteria criteria);

    /** Distinct action names present in the trail, for the filter dropdown. */
    java.util.List<String> distinctActions();
}
