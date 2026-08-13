package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

/**
 * Resolves who is performing the current operation, for stamping createdBy
 * on resources: the username under RBAC, the service name for a caller that
 * authenticated outside RBAC (the CI API), "system" outside a request scope
 * (scheduler/expiration workers), and null in legacy password mode where
 * there is no identity.
 */
@ApplicationScoped
public class ActorResolver {

    @Inject
    CurrentUser currentUser;

    public String usernameOrSystem() {
        try {
            // the CI API never populates the RBAC identity, so without this a
            // pipeline-created resource is indistinguishable from a legacy one
            return currentUser.isRbacActive()
                    ? currentUser.getUsername()
                    : currentUser.getServiceActor();
        } catch (ContextNotActiveException e) {
            return "system";
        }
    }
}
