package com.example.shortener.web;

import com.example.shortener.dto.ApiError;
import com.example.shortener.exception.ConflictException;
import com.example.shortener.exception.GoneException;
import com.example.shortener.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("maps each expected failure to its own status and code")
    void mapsApiExceptions() {
        assertThat(handler.handleApiException(new NotFoundException("nope")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleApiException(new GoneException("expired")).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        assertThat(handler.handleApiException(new ConflictException("taken")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("never leaks internal exception text to the client")
    void hidesInternalDetail() {
        ResponseEntity<ApiError> response =
                handler.handleUnexpected(new IllegalStateException("relation \"links\" does not exist"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain("links");
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
