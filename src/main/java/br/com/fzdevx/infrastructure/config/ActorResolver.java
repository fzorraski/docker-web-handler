package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

/**
 * Resolves who is performing the current operation, for stamping createdBy
 * on resources: the username under RBAC, "system" outside a request scope
 * (scheduler/expiration workers), and null in legacy password mode where
 * there is no identity.
 */
@ApplicationScoped
public class ActorResolver {

    @Inject
    CurrentUser currentUser;

    public String usernameOrSystem() {
        try {
            return currentUser.isRbacActive() ? currentUser.getUsername() : null;
        } catch (ContextNotActiveException e) {
            return "system";
        }
    }
}
