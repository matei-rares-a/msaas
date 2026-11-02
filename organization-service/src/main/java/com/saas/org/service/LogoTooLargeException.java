package com.saas.org.service;

/** The uploaded file is bigger than the configured maximum (REQ-PERS-03); mapped to 413. */
public class LogoTooLargeException extends RuntimeException {

    public LogoTooLargeException(String message) {
        super(message);
    }
}
