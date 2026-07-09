package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.auth.AuthSession;

import java.time.Instant;
import java.util.Optional;

/** Store for login sessions, keyed by the SHA-256 hash of the session token. */
public interface SessionRepository {

    void save(AuthSession session);

    Optional<AuthSession> find(String tokenHash);

    /** Bumps lastAccessedAt; no-op when the session does not exist. */
    void touch(String tokenHash, Instant lastAccessedAt);

    void delete(String tokenHash);

    void deleteForUser(String userId);

    void deleteForUserExcept(String userId, String tokenHashToKeep);

    /** Deletes sessions idle since before the cutoff; returns how many. */
    int deleteIdleSince(Instant cutoff);
}
