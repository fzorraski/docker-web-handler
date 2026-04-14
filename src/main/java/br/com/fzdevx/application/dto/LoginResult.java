package br.com.fzdevx.application.dto;

public record LoginResult(Status status, long retryAfterSeconds) {

    public enum Status {
        SUCCESS,
        INVALID_PASSWORD,
        RATE_LIMITED
    }

    public static LoginResult success() {
        return new LoginResult(Status.SUCCESS, 0);
    }

    public static LoginResult invalidPassword() {
        return new LoginResult(Status.INVALID_PASSWORD, 0);
    }

    public static LoginResult rateLimited(long retryAfterSeconds) {
        return new LoginResult(Status.RATE_LIMITED, retryAfterSeconds);
    }
}
