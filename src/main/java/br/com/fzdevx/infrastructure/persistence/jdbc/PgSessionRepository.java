package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.SessionRepository;
import br.com.fzdevx.domain.model.auth.AuthSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Durable login sessions - they survive application restarts. */
@ApplicationScoped
@Typed(PgSessionRepository.class)
public class PgSessionRepository implements SessionRepository {

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(AuthSession session) {
        jdbc.update("""
                INSERT INTO auth_session (token_hash, user_id, created_at, last_accessed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (token_hash) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    created_at = EXCLUDED.created_at,
                    last_accessed_at = EXCLUDED.last_accessed_at
                """,
                session.tokenHash(), session.userId(), session.createdAt(), session.lastAccessedAt());
    }

    @Override
    public Optional<AuthSession> find(String tokenHash) {
        return jdbc.queryOne("""
                SELECT token_hash, user_id, created_at, last_accessed_at
                FROM auth_session WHERE token_hash = ?
                """, PgSessionRepository::map, tokenHash);
    }

    @Override
    public void touch(String tokenHash, Instant lastAccessedAt) {
        jdbc.update("UPDATE auth_session SET last_accessed_at = ? WHERE token_hash = ?",
                lastAccessedAt, tokenHash);
    }

    @Override
    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM auth_session WHERE token_hash = ?", tokenHash);
    }

    @Override
    public void deleteForUser(String userId) {
        jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
    }

    @Override
    public void deleteForUserExcept(String userId, String tokenHashToKeep) {
        jdbc.update("DELETE FROM auth_session WHERE user_id = ? AND token_hash <> ?",
                userId, tokenHashToKeep);
    }

    @Override
    public int deleteIdleSince(Instant cutoff) {
        return jdbc.update("DELETE FROM auth_session WHERE last_accessed_at < ?", cutoff);
    }

    private static AuthSession map(ResultSet rs) throws SQLException {
        return new AuthSession(
                rs.getString("token_hash"),
                rs.getString("user_id"),
                JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "last_accessed_at"));
    }
}
