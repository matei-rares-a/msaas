package com.saas.auth.exception;

/** The account still belongs to at least one organization and so cannot be erased yet. */
public class AccountInUseException extends RuntimeException {

    public AccountInUseException(String message) {
        super(message);
    }
}
