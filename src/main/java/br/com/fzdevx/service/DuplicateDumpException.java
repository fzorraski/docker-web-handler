package br.com.fzdevx.service;

public class DuplicateDumpException extends RuntimeException {

    public DuplicateDumpException(String message) {
        super(message);
    }
}
