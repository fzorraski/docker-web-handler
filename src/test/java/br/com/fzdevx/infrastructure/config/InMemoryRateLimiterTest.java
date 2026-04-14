package br.com.fzdevx.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRateLimiterTest {

    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new InMemoryRateLimiter();
        setField("maxAttempts", 5);
        setField("baseDelaySeconds", 2);
        setField("maxDelaySeconds", 300);
        setField("cleanupIntervalMinutes", 10);
        setField("entryTtlMinutes", 60);
    }

    private void setField(String name, Object value) {
        try {
            Field f = InMemoryRateLimiter.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(rateLimiter, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- checkRateLimit ----

    @Test
    void checkRateLimit_noRecord_returnsEmpty() {
        Optional<Long> result = rateLimiter.checkRateLimit("login:1.2.3.4");
        assertTrue(result.isEmpty());
    }

    @Test
    void checkRateLimit_nullKey_returnsEmpty() {
        assertTrue(rateLimiter.checkRateLimit(null).isEmpty());
    }

    @Test
    void checkRateLimit_blankKey_returnsEmpty() {
        assertTrue(rateLimiter.checkRateLimit("  ").isEmpty());
    }

    // ---- recordFailure below threshold ----

    @Test
    void recordFailure_belowThreshold_doesNotBlock() {
        String key = "login:1.2.3.4";
        for (int i = 0; i < 4; i++) {
            rateLimiter.recordFailure(key);
            assertTrue(rateLimiter.checkRateLimit(key).isEmpty(),
                    "Should not block on failure #" + (i + 1));
        }
    }

    // ---- recordFailure at threshold ----

    @Test
    void recordFailure_atThreshold_blocks() {
        String key = "login:1.2.3.4";
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure(key);
        }
        Optional<Long> result = rateLimiter.checkRateLimit(key);
        assertTrue(result.isPresent());
        assertTrue(result.get() > 0);
        assertTrue(result.get() <= 2); // base delay = 2s
    }

    // ---- exponential growth ----

    @Test
    void recordFailure_exponentialGrowth() {
        String key = "login:1.2.3.4";
        // 5 failures → 2s, 6 failures → 4s, 7 failures → 8s
        for (int i = 0; i < 7; i++) {
            rateLimiter.recordFailure(key);
        }
        Optional<Long> result = rateLimiter.checkRateLimit(key);
        assertTrue(result.isPresent());
        // 7th failure: delay = 2 * 2^(7-5) = 8s
        assertTrue(result.get() > 4);
        assertTrue(result.get() <= 8);
    }

    // ---- delay capped at max ----

    @Test
    void recordFailure_cappedAtMaxDelay() {
        String key = "login:1.2.3.4";
        for (int i = 0; i < 20; i++) {
            rateLimiter.recordFailure(key);
        }
        Optional<Long> result = rateLimiter.checkRateLimit(key);
        assertTrue(result.isPresent());
        assertTrue(result.get() <= 300);
    }

    // ---- calculateDelay ----

    @Test
    void calculateDelay_atThreshold_returnsBase() {
        assertEquals(2, rateLimiter.calculateDelay(5));
    }

    @Test
    void calculateDelay_aboveThreshold_doubles() {
        assertEquals(4, rateLimiter.calculateDelay(6));
        assertEquals(8, rateLimiter.calculateDelay(7));
        assertEquals(16, rateLimiter.calculateDelay(8));
    }

    @Test
    void calculateDelay_highCount_cappedAtMax() {
        assertEquals(300, rateLimiter.calculateDelay(50));
    }

    // ---- recordSuccess ----

    @Test
    void recordSuccess_clearsRecord() {
        String key = "login:1.2.3.4";
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure(key);
        }
        assertTrue(rateLimiter.checkRateLimit(key).isPresent());

        rateLimiter.recordSuccess(key);

        assertTrue(rateLimiter.checkRateLimit(key).isEmpty());
        assertEquals(0, rateLimiter.getFailureCount(key));
    }

    @Test
    void recordSuccess_unknownKey_doesNotThrow() {
        assertDoesNotThrow(() -> rateLimiter.recordSuccess("unknown"));
    }

    @Test
    void recordSuccess_nullKey_doesNotThrow() {
        assertDoesNotThrow(() -> rateLimiter.recordSuccess(null));
    }

    // ---- getFailureCount ----

    @Test
    void getFailureCount_noRecord_returnsZero() {
        assertEquals(0, rateLimiter.getFailureCount("login:1.2.3.4"));
    }

    @Test
    void getFailureCount_afterFailures_returnsCount() {
        String key = "login:1.2.3.4";
        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        assertEquals(3, rateLimiter.getFailureCount(key));
    }

    @Test
    void getFailureCount_nullKey_returnsZero() {
        assertEquals(0, rateLimiter.getFailureCount(null));
    }

    // ---- independent keys ----

    @Test
    void independentKeys_doNotInterfere() {
        String keyA = "login:1.1.1.1";
        String keyB = "login:2.2.2.2";

        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure(keyA);
        }

        assertTrue(rateLimiter.checkRateLimit(keyA).isPresent());
        assertTrue(rateLimiter.checkRateLimit(keyB).isEmpty());
        assertEquals(0, rateLimiter.getFailureCount(keyB));
    }

    // ---- recordFailure with null/blank key ----

    @Test
    void recordFailure_nullKey_doesNotThrow() {
        assertDoesNotThrow(() -> rateLimiter.recordFailure(null));
    }

    @Test
    void recordFailure_blankKey_doesNotThrow() {
        assertDoesNotThrow(() -> rateLimiter.recordFailure("  "));
    }
}
