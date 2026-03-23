package br.com.fzdevx.domain.exception;


public final class DuplicateEntityException extends DomainException {

    public DuplicateEntityException(String message) {
        super("DUPLICATE", message);
    }
}
