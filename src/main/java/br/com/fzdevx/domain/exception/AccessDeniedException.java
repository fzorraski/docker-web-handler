package br.com.fzdevx.domain.exception;


public final class AccessDeniedException extends DomainException {

    public AccessDeniedException(String message) {
        super("FORBIDDEN", message);
    }
}
