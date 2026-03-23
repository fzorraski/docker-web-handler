package br.com.fzdevx.domain.exception;


public final class EntityNotFoundException extends DomainException {

    public EntityNotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}
