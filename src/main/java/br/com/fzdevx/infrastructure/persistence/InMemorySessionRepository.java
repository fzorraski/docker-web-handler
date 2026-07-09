package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.SessionRepository;
import br.com.fzdevx.domain.model.auth.AuthSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy session store for the file persistence backend: sessions live only
 * in this JVM's heap and are lost on restart (pre-PostgreSQL behavior).
 */
@ApplicationScoped
@Typed(InMemorySessionRepository.class)
public class InMemorySessionRepository implements SessionRepository {

    private final ConcurrentHashMap<String, AuthSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(AuthSession session) {
        sessions.put(session.tokenHash(), session);
    }

    @Override
    public Optional<AuthSession> find(String tokenHash) {
        return Optional.ofNullable(sessions.get(tokenHash));
    }

    @Override
    public void touch(String tokenHash, Instant lastAccessedAt) {
        sessions.computeIfPresent(tokenHash, (hash, session) -> session.touched(lastAccessedAt));
    }

    @Override
    public void delete(String tokenHash) {
        sessions.remove(tokenHash);
    }

    @Override
    public void deleteForUser(String userId) {
        sessions.values().removeIf(session -> userId.equals(session.userId()));
    }

    @Override
    public void deleteForUserExcept(String userId, String tokenHashToKeep) {
        sessions.entrySet().removeIf(entry -> userId.equals(entry.getValue().userId())
                && !entry.getKey().equals(tokenHashToKeep));
    }

    @Override
    public int deleteIdleSince(Instant cutoff) {
        int before = sessions.size();
        sessions.values().removeIf(session -> session.lastAccessedAt().isBefore(cutoff));
        return before - sessions.size();
    }
}
