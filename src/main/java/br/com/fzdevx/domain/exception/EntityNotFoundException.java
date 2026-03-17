package br.com.fzdevx.domain.exception;

// ✦ ARCH — maps to HTTP 404
public final class EntityNotFoundException extends DomainException {

    public EntityNotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}
