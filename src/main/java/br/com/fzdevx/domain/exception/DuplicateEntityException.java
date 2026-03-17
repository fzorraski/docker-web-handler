package br.com.fzdevx.domain.exception;

// ✦ ARCH — maps to HTTP 409
public final class DuplicateEntityException extends DomainException {

    public DuplicateEntityException(String message) {
        super("DUPLICATE", message);
    }
}
