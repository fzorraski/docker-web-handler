package br.com.fzdevx.infrastructure.config;

/**
 * Test wiring helper for {@link CallerIdentity}, which has a package-private
 * injected field: wraps an already populated (or throwing) {@link CurrentUser}.
 */
public final class TestCallerIdentity {

    private TestCallerIdentity() {
    }

    public static CallerIdentity of(CurrentUser currentUser) {
        CallerIdentity identity = new CallerIdentity();
        identity.currentUser = currentUser;
        return identity;
    }
}
