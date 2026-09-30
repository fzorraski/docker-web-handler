package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ContextNotActiveException;

/**
 * Single definition of how audit backends resolve the acting user: the
 * username under RBAC, "anonymous" in legacy password mode, and "system"
 * outside any request (e.g. scheduled executions). Both audit logger
 * implementations must agree on this - audit trails may not depend on the
 * configured persistence backend. Reads through {@link CallerIdentity}, so a
 * streaming call keeps its caller after the browser disconnects.
 */
public final class AuditActor {

    private AuditActor() {
    }

    public static String resolve(CallerIdentity callerIdentity) {
        try {
            CurrentUser currentUser = callerIdentity.user();
            if (currentUser.isRbacActive() && currentUser.getUsername() != null) {
                return currentUser.getUsername();
            }
            return "anonymous";
        } catch (ContextNotActiveException e) {
            return "system";
        }
    }
}
