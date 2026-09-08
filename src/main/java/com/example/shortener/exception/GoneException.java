package com.example.shortener.exception;

import org.springframework.http.HttpStatus;

/**
 * The link existed but is no longer usable (expired or disabled).
 * Distinct from 404 so callers can tell "never existed" from "no longer active".
 */
public class GoneException extends ApiException {
    public GoneException(String message) {
        super(HttpStatus.GONE, "LINK_GONE", message);
    }
}
