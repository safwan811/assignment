package com.example.shortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for expected, client-visible failures. Carrying the status on the exception keeps
 * the mapping in one place and stops controllers from leaking HTTP concerns into services.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
