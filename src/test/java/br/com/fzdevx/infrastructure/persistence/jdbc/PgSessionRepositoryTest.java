package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.domain.model.auth.AuthSession;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PostgresBackendProfile.class)
class PgSessionRepositoryTest {

    @Inject
    PgSessionRepository sessions;

    private static AuthSession session(String userId, Instant at) {
        return new AuthSession(UUID.randomUUID().toString(), userId, at, at);
    }

    @Test
    void sessionsSurviveAcrossRepositoryInstances_andTouchWorks() {
        Instant created = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthSession stored = session("user-1", created);
        sessions.save(stored);

        // durable: any reader (e.g. after an app restart) sees the session
        AuthSession found = sessions.find(stored.tokenHash()).orElseThrow();
        assertEquals("user-1", found.userId());
        assertEquals(created, found.lastAccessedAt());

        Instant later = created.plus(2, ChronoUnit.MINUTES);
        sessions.touch(stored.tokenHash(), later);
        assertEquals(later, sessions.find(stored.tokenHash()).orElseThrow().lastAccessedAt());

        sessions.delete(stored.tokenHash());
        assertTrue(sessions.find(stored.tokenHash()).isEmpty());
    }

    @Test
    void deleteForUserExcept_keepsOnlyTheGivenSession() {
        String userId = "user-" + UUID.randomUUID();
        Instant now = Instant.now();
        AuthSession keep = session(userId, now);
        AuthSession drop1 = session(userId, now);
        AuthSession drop2 = session(userId, now);
        sessions.save(keep);
        sessions.save(drop1);
        sessions.save(drop2);

        sessions.deleteForUserExcept(userId, keep.tokenHash());

        assertTrue(sessions.find(keep.tokenHash()).isPresent());
        assertTrue(sessions.find(drop1.tokenHash()).isEmpty());
        assertTrue(sessions.find(drop2.tokenHash()).isEmpty());

        sessions.deleteForUser(userId);
        assertTrue(sessions.find(keep.tokenHash()).isEmpty());
    }

    @Test
    void deleteIdleSince_removesOnlyExpired() {
        String userId = "user-" + UUID.randomUUID();
        Instant now = Instant.now();
        AuthSession fresh = session(userId, now);
        AuthSession idle = session(userId, now.minus(10, ChronoUnit.DAYS));
        sessions.save(fresh);
        sessions.save(idle);

        int removed = sessions.deleteIdleSince(now.minus(1, ChronoUnit.DAYS));

        assertTrue(removed >= 1);
        assertTrue(sessions.find(fresh.tokenHash()).isPresent());
        assertTrue(sessions.find(idle.tokenHash()).isEmpty());
        sessions.deleteForUser(userId);
    }
}
