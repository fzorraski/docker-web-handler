package br.com.fzdevx.domain.exception;


public sealed class DomainException extends RuntimeException
        permits EntityNotFoundException, DuplicateEntityException,
                InvalidInputException, OperationInProgressException,
                RateLimitedException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
