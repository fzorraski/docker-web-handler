package br.com.fzdevx.application.port;

/**
 * Records who did what for security-sensitive operations (container create/remove,
 * database restore/delete, user and role management, settings changes, login).
 * Implementations resolve the acting user from the current request when possible.
 *
 * <p>Entries are stamped with the acting user's tenant so the trail can be read
 * per tenant; reads take an explicit {@link br.com.fzdevx.application.dto.AuditScope}
 * rather than resolving it themselves, so a caller cannot forget to pass one.</p>
 */
public interface AuditLogger {

    /** Records an action performed by the current request's user. */
    void log(String action, String target, String detail);

    /** Records an action with an explicit actor, for flows without a request identity (e.g. login). */
    void logAs(String actor, String action, String target, String detail);

    /**
     * Records an action with both actor and tenant given explicitly, for flows
     * where neither can be resolved from the request: login (the RBAC identity
     * is only established by the call itself) and the terminal websocket (no
     * request scope). Without this those entries would carry no tenant and stay
     * invisible to the tenant's own admins.
     */
    void logForTenant(String actor, String tenantId, String action, String target, String detail);

    /**
     * Deletes entries older than the cutoff (retention job).
     * Returns the number of entries removed.
     */
    int removeEntriesOlderThan(java.time.Instant cutoff);

    /** Browses the trail, newest first, for the audit screen (AUDIT_LOG_VIEW). */
    br.com.fzdevx.application.dto.AuditSearchResult search(
            br.com.fzdevx.application.dto.AuditSearchCriteria criteria,
            br.com.fzdevx.application.dto.AuditScope scope);

    /** Distinct action names present in the readable trail, for the filter dropdown. */
    java.util.List<String> distinctActions(br.com.fzdevx.application.dto.AuditScope scope);
}
