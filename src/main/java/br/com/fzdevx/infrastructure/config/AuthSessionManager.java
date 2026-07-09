package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.SessionRepository;
import br.com.fzdevx.domain.model.auth.AuthSession;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Session facade over the {@link SessionRepository}: the raw token lives only
 * in the client's cookie, the store holds its SHA-256 hash. A short-TTL
 * in-memory cache keeps validateAndTouch off the store on every request, and
 * lastAccessedAt writes are throttled (one touch per interval per session).
 * Local invalidations purge the cache immediately, so revocation is instant
 * on this instance; the TTL only bounds staleness for future multi-instance
 * deployments.
 */
@ApplicationScoped
public class AuthSessionManager {

    private static final long CLEANUP_INTERVAL_MINUTES = 5;
    private static final long CACHE_TTL_SECONDS = 30;

    @Inject
    @ConfigProperty(name = "app.auth.enabled", defaultValue = "false")
    boolean authEnabled;

    @Inject
    @ConfigProperty(name = "app.auth.session.touch-interval-seconds", defaultValue = "60")
    long touchIntervalSeconds;

    @Inject
    RuntimeSettingsService runtimeSettings;

    @Inject
    SessionRepository sessionRepository;

    private record CachedSession(String userId, Instant lastAccessedAt, Instant cachedAt) {
    }

    private final ConcurrentHashMap<String, CachedSession> cache = new ConcurrentHashMap<>();

    /**
     * Bumped BEFORE every invalidation's store delete + cache purge. A cache
     * refill only lands when the epoch is unchanged since before its store
     * read, so an in-flight validation can never resurrect a session that was
     * revoked while it was reading - local revocation stays instant.
     */
    private final java.util.concurrent.atomic.AtomicLong invalidationEpoch =
            new java.util.concurrent.atomic.AtomicLong();

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
        String token = UUID.randomUUID().toString();
        String hash = hash(token);
        Instant now = Instant.now();
        sessionRepository.save(new AuthSession(hash, userId, now, now));
        cache.put(hash, new CachedSession(userId, now, now));
        return token;
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
        CachedSession session = validAndTouched(sessionId);
        return session == null ? Optional.empty() : Optional.ofNullable(session.userId());
    }

    private CachedSession validAndTouched(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String hash = hash(sessionId);
        Instant now = Instant.now();
        long timeoutSeconds = (long) runtimeSettings.getSessionTimeoutMinutes() * 60;
        Instant cutoff = now.minusSeconds(timeoutSeconds);
        long epoch = invalidationEpoch.get();

        CachedSession cached = cache.get(hash);
        if (cached == null || cached.cachedAt().isBefore(now.minusSeconds(CACHE_TTL_SECONDS))) {
            AuthSession stored = sessionRepository.find(hash).orElse(null);
            if (stored == null) {
                cache.remove(hash);
                return null;
            }
            cached = new CachedSession(stored.userId(), stored.lastAccessedAt(), now);
            putIfNoInvalidationSince(epoch, hash, cached);
        }

        if (cached.lastAccessedAt().isBefore(cutoff)) {
            sessionRepository.delete(hash);
            cache.remove(hash);
            return null;
        }

        // Throttled touch: at most one lastAccessedAt write per interval per
        // session. The effective interval is capped at half the timeout so an
        // actively used session can never idle out just because its touches
        // were throttled (e.g. timeout 1 min with the default 60s interval).
        long effectiveTouchInterval = Math.min(touchIntervalSeconds, Math.max(1, timeoutSeconds / 2));
        if (cached.lastAccessedAt().isBefore(now.minusSeconds(effectiveTouchInterval))) {
            sessionRepository.touch(hash, now);
            cached = new CachedSession(cached.userId(), now, now);
            putIfNoInvalidationSince(epoch, hash, cached);
        }
        return cached;
    }

    /**
     * Caches the entry only when no invalidation ran since the caller read the
     * store. Invalidators bump the epoch BEFORE deleting/purging, so either
     * this check fails (no put) or the invalidator's purge runs after our put
     * and removes it - a revoked session can never stick in the cache.
     */
    private void putIfNoInvalidationSince(long epoch, String hash, CachedSession cached) {
        if (invalidationEpoch.get() == epoch) {
            cache.put(hash, cached);
        }
    }

    public void invalidateSession(String sessionId) {
        if (sessionId != null) {
            String hash = hash(sessionId);
            invalidationEpoch.incrementAndGet();
            sessionRepository.delete(hash);
            cache.remove(hash);
        }
    }

    public void invalidateSessionsForUser(String userId) {
        if (userId == null) return;
        invalidationEpoch.incrementAndGet();
        sessionRepository.deleteForUser(userId);
        cache.values().removeIf(session -> userId.equals(session.userId()));
    }

    /** Invalidates all of a user's sessions except the given one (e.g. keep the session that just changed its own password). */
    public void invalidateSessionsForUserExcept(String userId, String sessionIdToKeep) {
        if (userId == null) return;
        String keepHash = sessionIdToKeep == null ? "" : hash(sessionIdToKeep);
        invalidationEpoch.incrementAndGet();
        sessionRepository.deleteForUserExcept(userId, keepHash);
        cache.entrySet().removeIf(entry -> userId.equals(entry.getValue().userId())
                && !entry.getKey().equals(keepHash));
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public int getSessionTimeoutMinutes() {
        return runtimeSettings.getSessionTimeoutMinutes();
    }

    private void evictExpired() {
        try {
            Instant now = Instant.now();
            Instant cutoff = now.minusSeconds((long) runtimeSettings.getSessionTimeoutMinutes() * 60);
            int evicted = sessionRepository.deleteIdleSince(cutoff);
            cache.entrySet().removeIf(entry ->
                    entry.getValue().cachedAt().isBefore(now.minusSeconds(CACHE_TTL_SECONDS))
                            || entry.getValue().lastAccessedAt().isBefore(cutoff));
            if (evicted > 0) {
                Log.infof("AuthSessionManager: evicted %d expired session(s).", evicted);
            }
        } catch (Exception e) {
            Log.errorf(e, "Session eviction failed.");
        }
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
