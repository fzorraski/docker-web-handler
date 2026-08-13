package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActorResolverTest {

    private static ActorResolver resolverFor(CurrentUser currentUser) {
        ActorResolver resolver = new ActorResolver();
        resolver.currentUser = currentUser;
        return resolver;
    }

    @Test
    void usernameOrSystem_underRbac_returnsUsername() {
        CurrentUser user = new CurrentUser();
        user.set("u1", "alice", Set.of(Permission.DATABASE_VIEW));

        assertEquals("alice", resolverFor(user).usernameOrSystem());
    }

    @Test
    void usernameOrSystem_legacyPasswordMode_returnsNull() {
        assertNull(resolverFor(new CurrentUser()).usernameOrSystem());
    }

    @Test
    void usernameOrSystem_apiKeyCaller_returnsServiceActor() {
        CurrentUser user = new CurrentUser();
        user.setServiceActor("ci");

        assertEquals("ci", resolverFor(user).usernameOrSystem());
    }

    @Test
    void usernameOrSystem_rbacUserWins_overServiceActor() {
        CurrentUser user = new CurrentUser();
        user.setServiceActor("ci");
        user.set("u1", "alice", Set.of());

        assertEquals("alice", resolverFor(user).usernameOrSystem());
    }

    @Test
    void usernameOrSystem_outsideRequestScope_returnsSystem() {
        // scheduler and expiration workers have no request context at all
        CurrentUser detached = mock(CurrentUser.class);
        when(detached.isRbacActive()).thenThrow(new ContextNotActiveException());

        assertEquals("system", resolverFor(detached).usernameOrSystem());
    }
}
