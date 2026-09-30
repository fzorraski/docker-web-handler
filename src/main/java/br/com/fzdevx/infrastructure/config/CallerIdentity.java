package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.util.concurrent.Callable;

/**
 * The single door to "who is performing this operation", surviving the loss of
 * the request scope.
 *
 * <p>Long operations (container run, restore, upgrade, migration, prune) stream
 * their progress over SSE and, by design, keep running after the browser drops
 * the connection - dialog closed, page reloaded, network blip, or the client
 * closing on a non-fatal ERROR event. At the next event write RESTEasy closes the
 * sink and completes the async response, and Quarkus terminates the request
 * scope from that thread while the worker thread is still inside the use case.
 * From then on every call on the request-scoped {@link CurrentUser} proxy throws
 * {@link ContextNotActiveException}, and the actor and tenant resolvers fell back
 * to "system"/no tenant: a person's container label, database creator, audit
 * entries and ownership checks were mislabelled as system work.</p>
 *
 * <p>{@link #user()} returns the live request user while the scope is active,
 * otherwise the detached copy bound to the thread by {@link PreserveCallerIdentity}
 * for the duration of the streaming call. With neither (scheduler and expiration
 * workers) it still throws {@link ContextNotActiveException}, so the callers'
 * existing "no request identity" branches keep their meaning.</p>
 */
@ApplicationScoped
public class CallerIdentity {

    private static final ThreadLocal<CurrentUser> BOUND = new ThreadLocal<>();

    @Inject
    CurrentUser currentUser;

    /**
     * The caller's identity.
     *
     * @throws ContextNotActiveException when there is no request scope and no
     *                                   identity was preserved for this thread
     */
    public CurrentUser user() {
        try {
            // any call goes through the client proxy, which throws once the scope is gone
            currentUser.isRbacActive();
            return currentUser;
        } catch (ContextNotActiveException e) {
            CurrentUser bound = BOUND.get();
            if (bound == null) {
                throw e;
            }
            return bound;
        }
    }

    /** A detached copy of the caller, or null when there is no identity to preserve. */
    public CurrentUser capture() {
        try {
            return user().detachedCopy();
        } catch (ContextNotActiveException e) {
            return null;
        }
    }

    /**
     * Runs {@code work} with {@code identity} answering {@link #user()} on this
     * thread whenever the request scope is unavailable. Restores the previous
     * binding afterwards, so worker-pool threads never leak an identity.
     */
    public <T> T callAs(CurrentUser identity, Callable<T> work) throws Exception {
        CurrentUser previous = BOUND.get();
        if (identity != null) {
            BOUND.set(identity);
        }
        try {
            return work.call();
        } finally {
            if (previous == null) {
                BOUND.remove();
            } else {
                BOUND.set(previous);
            }
        }
    }
}
