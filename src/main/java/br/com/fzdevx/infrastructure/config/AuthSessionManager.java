package br.com.fzdevx.infrastructure.config;

import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AuthSessionManager {

    private static final long CLEANUP_INTERVAL_MINUTES = 5;

    @Inject
    @ConfigProperty(name = "app.auth.enabled", defaultValue = "false")
    boolean authEnabled;

    @Inject
    @ConfigProperty(name = "app.auth.session-timeout-minutes", defaultValue = "480")
    int sessionTimeoutMinutes;

    private static class Session {
        final Instant createdAt;
        final String userId; // null in legacy single-password mode
        volatile Instant lastAccessedAt;

        Session(Instant now, String userId) {
            this.createdAt = now;
            this.userId = userId;
            this.lastAccessedAt = now;
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupScheduler;

    @PostConstruct
    void init() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auth-session-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::evictExpired,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
        }
    }

    public String createSession() {
        return createSession(null);
    }

    public String createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new Session(Instant.now(), userId));
        return sessionId;
    }

    public boolean validateAndTouch(String sessionId) {
        return validAndTouched(sessionId) != null;
    }

    /**
     * Validates and touches the session, returning the user id it was created
     * for. Empty means the session is invalid/expired OR it is a legacy
     * single-password session (which has no user id) - use
     * {@link #validateAndTouch(String)} to test validity alone.
     */
    public Optional<String> getUserIdIfValid(String sessionId) {
        Session session = validAndTouched(sessionId);
        return session == null ? Optional.empty() : Optional.ofNullable(session.userId);
    }

    private Session validAndTouched(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;

        Session session = sessions.get(sessionId);
        if (session == null) return null;

        Instant cutoff = Instant.now().minusSeconds((long) sessionTimeoutMinutes * 60);
        if (session.lastAccessedAt.isBefore(cutoff)) {
            sessions.remove(sessionId);
            return null;
        }

        session.lastAccessedAt = Instant.now();
        return session;
    }

    public void invalidateSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public void invalidateSessionsForUser(String userId) {
        if (userId == null) return;
        sessions.entrySet().removeIf(entry -> userId.equals(entry.getValue().userId));
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds((long) sessionTimeoutMinutes * 60);
        int evicted = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getValue().lastAccessedAt.isBefore(cutoff)) {
                sessions.remove(entry.getKey());
                evicted++;
            }
        }
        if (evicted > 0) {
            Log.infof("AuthSessionManager: evicted %d expired session(s).", evicted);
        }
    }
}
