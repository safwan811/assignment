package com.example.shortener.service;

import com.example.shortener.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer();

    @ParameterizedTest
    @CsvSource({
            "HTTPS://Example.COM/Path,           https://example.com/Path",
            "https://example.com:443/path,       https://example.com/path",
            "http://example.com:80/path,         http://example.com/path",
            "https://example.com,                https://example.com/",
            "https://example.com/a/../b,         https://example.com/b"
    })
    @DisplayName("canonicalises scheme, host, default port and path but preserves path case")
    void normalisesCanonicalParts(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("preserves the query string: it usually selects the actual destination page")
    void preservesQuery() {
        assertThat(normalizer.normalize("https://example.com/product?id=100&ref=abc"))
                .isEqualTo("https://example.com/product?id=100&ref=abc");
    }

    @Test
    @DisplayName("drops the fragment, which is never sent to the server")
    void dropsFragment() {
        assertThat(normalizer.normalize("https://example.com/docs#section-3"))
                .isEqualTo("https://example.com/docs");
    }

    @Test
    @DisplayName("two URLs differing only in tracking params do NOT dedupe (documented trade-off)")
    void queryParamsAffectIdentity() {
        String a = normalizer.normalize("https://example.com/p?utm_source=email");
        String b = normalizer.normalize("https://example.com/p");
        assertThat(a).isNotEqualTo(b);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "mailto:someone@example.com"
    })
    @DisplayName("rejects any scheme outside the http/https allowlist")
    void rejectsDisallowedSchemes(String input) {
        assertThatThrownBy(() -> normalizer.normalize(input))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url", "https://", "http:///path"})
    @DisplayName("rejects blank and structurally invalid input")
    void rejectsInvalidInput(String input) {
        assertThatThrownBy(() -> normalizer.normalize(input))
                .isInstanceOf(BadRequestException.class);
    }
}
