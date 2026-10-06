package com.saas.auth.exception;

/** Thrown when the password-reset rate limit (REQ-PWD-05) is exceeded; mapped to 429. */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
