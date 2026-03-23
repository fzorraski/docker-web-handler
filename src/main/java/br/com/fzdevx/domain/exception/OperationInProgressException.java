package br.com.fzdevx.domain.exception;


public final class OperationInProgressException extends DomainException {

    public OperationInProgressException(String message) {
        super("OPERATION_IN_PROGRESS", message);
    }
}
