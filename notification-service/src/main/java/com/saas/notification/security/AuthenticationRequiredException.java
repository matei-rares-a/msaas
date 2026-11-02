package com.saas.notification.security;

/** The request reached the journal API without the identity the gateway sets from the token (REQ-AUTH-05). */
public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
