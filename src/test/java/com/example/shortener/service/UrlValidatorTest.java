package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(properties(true, 2048));

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/admin",
            "http://localhost/admin",
            "http://10.0.0.5/internal",
            "http://192.168.1.1/router",
            "http://172.16.0.1/service",
            "http://169.254.169.254/latest/meta-data/",   // cloud instance metadata
            "http://0.0.0.0/",
            "http://[::1]/admin"
    })
    @DisplayName("rejects loopback, private, link-local and wildcard targets")
    void rejectsInternalTargets(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/page",
            "https://8.8.8.8/",
            "https://sub.domain.example.co.uk/a/b?c=d"
    })
    @DisplayName("allows ordinary public destinations")
    void allowsPublicTargets(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforces the maximum URL length")
    void rejectsOverlongUrls() {
        String longUrl = "https://example.com/" + "a".repeat(3000);
        assertThatThrownBy(() -> validator.validate(longUrl))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximum length");
    }

    @Test
    @DisplayName("private-address blocking can be disabled for trusted internal deployments")
    void blockingIsConfigurable() {
        UrlValidator permissive = new UrlValidator(properties(false, 2048));
        assertThatCode(() -> permissive.validate("http://10.0.0.5/internal")).doesNotThrowAnyException();
    }

    private ShortenerProperties properties(boolean blockPrivate, int maxLength) {
        return new ShortenerProperties("http://localhost:8080", 7, 5, maxLength,
                Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(20),
                Duration.ofDays(1825), 60, blockPrivate, List.of("api"));
    }
}
