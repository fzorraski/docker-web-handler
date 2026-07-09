package br.com.fzdevx.domain.model.auth;

import java.time.Instant;

/**
 * A login session. Only the SHA-256 hash of the session token is stored -
 * the raw token exists solely in the client's cookie, so a leaked session
 * store does not yield usable tokens. userId is null for sessions created
 * in legacy single-password mode.
 */
public record AuthSession(String tokenHash, String userId, Instant createdAt, Instant lastAccessedAt) {

    public AuthSession touched(Instant at) {
        return new AuthSession(tokenHash, userId, createdAt, at);
    }
}
