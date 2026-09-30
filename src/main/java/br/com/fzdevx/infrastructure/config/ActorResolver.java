package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

/**
 * Resolves who is performing the current operation, for stamping createdBy
 * on resources: the username under RBAC, the service name for a caller that
 * authenticated outside RBAC (the CI API), "system" outside a request scope
 * (scheduler/expiration workers), and null in legacy password mode where
 * there is no identity. Reads through {@link CallerIdentity}, so a streaming
 * call keeps its caller after the browser disconnects.
 */
@ApplicationScoped
public class ActorResolver {

    @Inject
    CallerIdentity callerIdentity;

    public String usernameOrSystem() {
        try {
            CurrentUser user = callerIdentity.user();
            // the CI API never populates the RBAC identity, so without this a
            // pipeline-created resource is indistinguishable from a legacy one
            return user.isRbacActive()
                    ? user.getUsername()
                    : user.getServiceActor();
        } catch (ContextNotActiveException e) {
            return "system";
        }
    }
}
