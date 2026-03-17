package br.com.fzdevx.domain.exception;

// ✦ ARCH — maps to HTTP 400
public final class InvalidInputException extends DomainException {

    public InvalidInputException(String message) {
        super("INVALID_INPUT", message);
    }
}
