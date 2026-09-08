package com.example.shortener.exception;

import org.springframework.http.HttpStatus;

public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }
}
