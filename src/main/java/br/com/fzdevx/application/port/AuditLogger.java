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
}
