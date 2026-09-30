package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PreserveCallerIdentityInterceptorTest {

    /**
     * Plays the real sequence: the scope is active when the SSE method is
     * entered, then Quarkus terminates it mid-call (browser disconnected) and
     * the proxy starts throwing.
     */
    @Test
    void preserve_keepsTheEntryCallerAfterTheScopeIsTerminated() throws Exception {
        // a spy keeps the real state and copy logic; the stub below is the scope dying
        CurrentUser proxy = spy(new CurrentUser());
        proxy.set("u1", "alice", Set.of(), Set.of("t1"));
        CallerIdentity identity = TestCallerIdentity.of(proxy);
        ActorResolver resolver = new ActorResolver();
        resolver.callerIdentity = identity;
        PreserveCallerIdentityInterceptor interceptor = new PreserveCallerIdentityInterceptor();
        interceptor.callerIdentity = identity;

        AtomicReference<String> afterDisconnect = new AtomicReference<>();
        InvocationContext call = mock(InvocationContext.class);
        when(call.proceed()).thenAnswer(inv -> {
            // the run is in flight when the request scope goes away
            doThrow(new ContextNotActiveException()).when(proxy).isRbacActive();
            afterDisconnect.set(resolver.usernameOrSystem());
            return "done";
        });

        assertEquals("done", interceptor.preserve(call));
        assertEquals("alice", afterDisconnect.get());
        // nothing leaks to the next request served by this thread
        assertThrows(ContextNotActiveException.class, identity::user);
        assertEquals("system", resolver.usernameOrSystem());
    }

    @Test
    void preserve_withoutRequestIdentity_justProceeds() throws Exception {
        CurrentUser gone = mock(CurrentUser.class);
        when(gone.isRbacActive()).thenThrow(new ContextNotActiveException());
        PreserveCallerIdentityInterceptor interceptor = new PreserveCallerIdentityInterceptor();
        interceptor.callerIdentity = TestCallerIdentity.of(gone);
        InvocationContext call = mock(InvocationContext.class);
        when(call.proceed()).thenReturn("ok");

        assertEquals("ok", interceptor.preserve(call));
        assertThrows(ContextNotActiveException.class, interceptor.callerIdentity::user);
    }
}
