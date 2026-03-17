package br.com.fzdevx.domain.exception;

// ✦ ARCH — sealed domain exception hierarchy for consistent error handling
public sealed class DomainException extends RuntimeException
        permits EntityNotFoundException, DuplicateEntityException,
                InvalidInputException, OperationInProgressException {

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
