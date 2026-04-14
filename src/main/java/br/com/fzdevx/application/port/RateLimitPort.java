package br.com.fzdevx.application.port;

import java.util.Optional;

public interface RateLimitPort {

    /**
     * Check if the given key is currently rate-limited.
     *
     * @return empty if the attempt is allowed, or the number of seconds remaining until the block expires
     */
    Optional<Long> checkRateLimit(String key);

    /**
     * Record a failed authentication attempt for the given key.
     * May trigger a backoff window after the configured threshold is reached.
     */
    void recordFailure(String key);

    /**
     * Reset the rate limit state for a key after a successful authentication.
     */
    void recordSuccess(String key);

    /**
     * Get the current consecutive failure count for a key.
     */
    int getFailureCount(String key);
}
