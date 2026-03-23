package br.com.fzdevx.domain.exception;


public final class InvalidInputException extends DomainException {

    public InvalidInputException(String message) {
        super("INVALID_INPUT", message);
    }
}
