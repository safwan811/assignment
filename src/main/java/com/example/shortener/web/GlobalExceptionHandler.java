package com.example.shortener.web;

import com.example.shortener.dto.ApiError;
import com.example.shortener.exception.ApiException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Single place where exceptions become HTTP responses.
 *
 * Note the split: expected failures are returned with their own code and message, but anything
 * unexpected returns a generic message. Internal exception text can leak schema names, SQL, and
 * library internals, so it goes to the log, not to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e) {
        return ResponseEntity.status(e.status()).body(error(e.code(), e.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(error("INVALID_REQUEST", "Request validation failed", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException e) {
        return ResponseEntity.badRequest()
                .body(error("INVALID_REQUEST", "Request validation failed", List.of(e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(error("INVALID_REQUEST", "Request body is missing or malformed", List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "An unexpected error occurred", List.of()));
    }

    private ApiError error(String code, String message, List<String> details) {
        return new ApiError(code, message, details, MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now());
    }
}
