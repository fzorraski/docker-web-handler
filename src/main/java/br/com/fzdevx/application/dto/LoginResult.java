package br.com.fzdevx.application.dto;

public record LoginResult(Status status, long retryAfterSeconds, String userId) {

    public enum Status {
        SUCCESS,
        INVALID_PASSWORD,
        RATE_LIMITED
    }

    public static LoginResult success() {
        return new LoginResult(Status.SUCCESS, 0, null);
    }

    public static LoginResult success(String userId) {
        return new LoginResult(Status.SUCCESS, 0, userId);
    }

    public static LoginResult invalidPassword() {
        return new LoginResult(Status.INVALID_PASSWORD, 0, null);
    }

    public static LoginResult rateLimited(long retryAfterSeconds) {
        return new LoginResult(Status.RATE_LIMITED, retryAfterSeconds, null);
    }
}
