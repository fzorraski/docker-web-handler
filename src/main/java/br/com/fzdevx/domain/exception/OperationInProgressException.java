package br.com.fzdevx.domain.exception;

// ✦ ARCH — maps to HTTP 409
public final class OperationInProgressException extends DomainException {

    public OperationInProgressException(String message) {
        super("OPERATION_IN_PROGRESS", message);
    }
}
