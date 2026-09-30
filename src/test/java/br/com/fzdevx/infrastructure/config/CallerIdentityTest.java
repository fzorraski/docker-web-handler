package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallerIdentityTest {

    /** Stands in for the request-scoped proxy after Quarkus terminated the scope. */
    private static CurrentUser terminatedScope() {
        CurrentUser gone = mock(CurrentUser.class);
        when(gone.isRbacActive()).thenThrow(new ContextNotActiveException());
        return gone;
    }

    private static CurrentUser alice() {
        CurrentUser alice = new CurrentUser();
        alice.set("u1", "alice", Set.of(Permission.CONTAINERS_RUN), Set.of("t1"));
        return alice;
    }

    @Test
    void user_withActiveScope_returnsLiveUser() throws Exception {
        CurrentUser live = alice();
        CallerIdentity identity = TestCallerIdentity.of(live);

        assertSame(live, identity.user());
        // a live scope always wins over a stale binding
        assertSame(live, identity.callAs(new CurrentUser(), identity::user));
    }

    @Test
    void user_withoutScopeAndNothingBound_throws() {
        CallerIdentity identity = TestCallerIdentity.of(terminatedScope());

        assertThrows(ContextNotActiveException.class, identity::user);
    }

    @Test
    void user_withoutScope_returnsBoundIdentityOnlyDuringCall() throws Exception {
        CallerIdentity identity = TestCallerIdentity.of(terminatedScope());
        CurrentUser preserved = alice();

        CurrentUser seen = identity.callAs(preserved, identity::user);

        assertSame(preserved, seen);
        assertThrows(ContextNotActiveException.class, identity::user);
    }

    @Test
    void callAs_restoresPreviousBinding_evenWhenWorkFails() throws Exception {
        CallerIdentity identity = TestCallerIdentity.of(terminatedScope());
        CurrentUser outer = alice();
        CurrentUser inner = new CurrentUser();
        inner.setServiceActor("ci");

        identity.callAs(outer, () -> {
            assertThrows(IllegalStateException.class, () -> identity.callAs(inner, () -> {
                assertSame(inner, identity.user());
                throw new IllegalStateException("boom");
            }));
            assertSame(outer, identity.user());
            return null;
        });
    }

    @Test
    void callAs_nullIdentity_leavesBindingUntouched() throws Exception {
        CallerIdentity identity = TestCallerIdentity.of(terminatedScope());
        CurrentUser outer = alice();

        identity.callAs(outer, () -> identity.callAs(null, () -> {
            assertSame(outer, identity.user());
            return null;
        }));
    }

    @Test
    void capture_detachesFromTheRequestUser() {
        CurrentUser live = alice();
        live.setServiceActor("ci");
        CallerIdentity identity = TestCallerIdentity.of(live);

        CurrentUser copy = identity.capture();

        assertNotSame(live, copy);
        assertEquals("alice", copy.getUsername());
        assertEquals("u1", copy.getUserId());
        assertTrue(copy.isRbacActive());
        assertTrue(copy.hasPermission(Permission.CONTAINERS_RUN));
        assertEquals(Set.of("t1"), copy.getTenantIds());
        assertEquals("ci", copy.getServiceActor());
        // later mutation of the request user does not leak into the copy
        live.set("u2", "bob", Set.of());
        assertEquals("alice", copy.getUsername());
    }

    @Test
    void capture_legacyMode_keepsRbacInactive() {
        CurrentUser copy = TestCallerIdentity.of(new CurrentUser()).capture();

        assertNull(copy.getUsername());
        assertTrue(!copy.isRbacActive());
        assertTrue(copy.hasPermission(Permission.SYSTEM_CONFIG));
    }

    @Test
    void capture_withoutAnyIdentity_returnsNull() {
        assertNull(TestCallerIdentity.of(terminatedScope()).capture());
    }
}
