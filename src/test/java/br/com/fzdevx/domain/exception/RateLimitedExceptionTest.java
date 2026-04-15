package br.com.fzdevx.domain.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitedExceptionTest {

    @Test
    void constructor_setsRetryAfterSeconds() {
        var ex = new RateLimitedException(30);
        assertEquals(30, ex.getRetryAfterSeconds());
    }

    @Test
    void constructor_setsCode() {
        var ex = new RateLimitedException(10);
        assertEquals("TOO_MANY_REQUESTS", ex.getCode());
    }

    @Test
    void constructor_setsMessageWithSeconds() {
        var ex = new RateLimitedException(45);
        assertEquals("Too many failed attempts. Try again in 45 seconds.", ex.getMessage());
    }

    @Test
    void isDomainException() {
        var ex = new RateLimitedException(1);
        assertInstanceOf(DomainException.class, ex);
    }

    @Test
    void isRuntimeException() {
        var ex = new RateLimitedException(1);
        assertInstanceOf(RuntimeException.class, ex);
    }
}
