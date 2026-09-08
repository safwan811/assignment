package com.example.shortener.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fault injection: this context points at a Redis that is not there.
 *
 * Pointing at a closed port rather than stopping the shared container, because stopping it would
 * change its mapped port and break every other test in the run. The service must degrade to
 * PostgreSQL for reads and must still accept writes.
 *
 * This is the test behind the "graceful degradation" claim in the architecture document. Without
 * it that claim would be an assertion rather than a verified property.
 */
class CacheDegradationIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.registerPostgres(registry);
        // Reserved discard port: connections fail fast rather than hanging.
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 9);
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("shortener.create-requests-per-minute", () -> 100_000);
    }

    @Test
    @DisplayName("link creation succeeds with Redis unavailable: the rate limiter fails open")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void creationSucceedsWithoutRedis() {
        ResponseEntity<Map> response = rest.postForEntity(baseUrl() + "/api/v1/links",
                Map.of("url", "https://example.com/created-during-outage"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("redirects still resolve with Redis unavailable, by falling back to PostgreSQL")
    void redirectsFallBackToDatabase() {
        String code = create("https://example.com/resilient");

        ResponseEntity<Void> redirect = rest.getForEntity(baseUrl() + "/" + code, Void.class);

        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/resilient");
    }

    @Test
    @DisplayName("a disabled link is still 410 with Redis unavailable: correctness does not depend on the cache")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void disabledLinkStillGoneWithoutRedis() {
        String code = create("https://example.com/to-disable");
        rest.delete(baseUrl() + "/api/v1/links/" + code);

        ResponseEntity<Map> response = rest.getForEntity(baseUrl() + "/" + code, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}
