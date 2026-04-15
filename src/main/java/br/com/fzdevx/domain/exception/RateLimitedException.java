package br.com.fzdevx.domain.exception;


public final class RateLimitedException extends DomainException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("TOO_MANY_REQUESTS", "Too many failed attempts. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
