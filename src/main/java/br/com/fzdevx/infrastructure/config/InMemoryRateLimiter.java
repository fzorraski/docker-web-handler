package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RateLimitPort;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@ApplicationScoped
public class InMemoryRateLimiter implements RateLimitPort {

    static final int MAX_TRACKED_KEYS = 10_000;

    @Inject
    @ConfigProperty(name = "app.rate-limit.max-attempts", defaultValue = "5")
    int maxAttempts;

    @Inject
    @ConfigProperty(name = "app.rate-limit.base-delay-seconds", defaultValue = "2")
    int baseDelaySeconds;

    @Inject
    @ConfigProperty(name = "app.rate-limit.max-delay-seconds", defaultValue = "300")
    int maxDelaySeconds;

    @Inject
    @ConfigProperty(name = "app.rate-limit.cleanup-interval-minutes", defaultValue = "10")
    int cleanupIntervalMinutes;

    @Inject
    @ConfigProperty(name = "app.rate-limit.entry-ttl-minutes", defaultValue = "60")
    int entryTtlMinutes;

    private static class AttemptRecord {
        volatile int failureCount;
        volatile Instant lastFailureAt;
        volatile Instant blockedUntil;

        AttemptRecord() {
            this.failureCount = 0;
            this.lastFailureAt = Instant.now();
            this.blockedUntil = null;
        }
    }

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupScheduler;

    @PostConstruct
    void init() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::evictStale,
                cleanupIntervalMinutes, cleanupIntervalMinutes, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
        }
    }

    @Override
    public Optional<Long> checkRateLimit(String key) {
        if (key == null || key.isBlank()) return Optional.empty();

        // Use compute to make the read-and-clear-expired operation atomic
        var ref = new Object() { Optional<Long> result = Optional.empty(); };
        attempts.computeIfPresent(key, (k, record) -> {
            if (record.blockedUntil == null) return record;
            long remaining = record.blockedUntil.getEpochSecond() - Instant.now().getEpochSecond();
            if (remaining <= 0) {
                record.blockedUntil = null;
                return record;
            }
            ref.result = Optional.of(remaining);
            return record;
        });
        return ref.result;
    }

    @Override
    public void recordFailure(String key) {
        if (key == null || key.isBlank()) return;

        if (attempts.size() >= MAX_TRACKED_KEYS && !attempts.containsKey(key)) {
            Log.warnf("Rate limiter at capacity (%d keys). Dropping new entry for '%s'.", MAX_TRACKED_KEYS, key);
            return;
        }

        attempts.compute(key, (k, existing) -> {
            if (existing == null) existing = new AttemptRecord();
            existing.failureCount++;
            existing.lastFailureAt = Instant.now();
            if (existing.failureCount >= maxAttempts) {
                long delaySeconds = calculateDelay(existing.failureCount);
                existing.blockedUntil = Instant.now().plusSeconds(delaySeconds);
                Log.warnf("Rate limit: attempt #%d for '%s'. Blocked for %d seconds.",
                        existing.failureCount, k, delaySeconds);
            }
            return existing;
        });
    }

    @Override
    public void recordSuccess(String key) {
        if (key != null && !key.isBlank()) {
            attempts.remove(key);
        }
    }

    @Override
    public int getFailureCount(String key) {
        if (key == null || key.isBlank()) return 0;
        AttemptRecord record = attempts.get(key);
        return record != null ? record.failureCount : 0;
    }

    long calculateDelay(int failureCount) {
        int exponent = failureCount - maxAttempts;
        long delay = baseDelaySeconds * (1L << Math.min(exponent, 30));
        return Math.min(delay, maxDelaySeconds);
    }

    private void evictStale() {
        Instant cutoff = Instant.now().minusSeconds((long) entryTtlMinutes * 60);
        int evicted = 0;
        for (Map.Entry<String, AttemptRecord> entry : attempts.entrySet()) {
            if (entry.getValue().lastFailureAt.isBefore(cutoff)) {
                attempts.remove(entry.getKey());
                evicted++;
            }
        }
        if (evicted > 0) {
            Log.infof("RateLimiter: evicted %d stale entry(ies).", evicted);
        }
    }
}
