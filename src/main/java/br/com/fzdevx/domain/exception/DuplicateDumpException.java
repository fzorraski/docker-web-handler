package br.com.fzdevx.domain.exception;

public class DuplicateDumpException extends RuntimeException {

    public DuplicateDumpException(String message) {
        super(message);
    }
}
