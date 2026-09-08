package com.example.shortener.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs in its own context with a deliberately tiny limit, so the boundary is reachable without
 * hammering the service and without leaking a low limit into the other integration tests.
 */
class RateLimitIT extends AbstractIntegrationTest {

    private static final int LIMIT = 5;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.registerPostgres(registry);
        Containers.registerRedis(registry);
        registry.add("shortener.create-requests-per-minute", () -> LIMIT);
    }

    @Test
    @DisplayName("creation is throttled past the configured limit, with 429 and a machine-readable code")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void throttlesCreation() {
        // Unique suffix so a retry of this test in the same minute does not inherit the previous
        // window's counter for this client.
        String run = UUID.randomUUID().toString().substring(0, 8);

        for (int i = 0; i < LIMIT; i++) {
            ResponseEntity<Map> ok = rest.postForEntity(baseUrl() + "/api/v1/links",
                    Map.of("url", "https://example.com/" + run + "/" + i), Map.class);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<Map> limited = rest.postForEntity(baseUrl() + "/api/v1/links",
                Map.of("url", "https://example.com/" + run + "/over"), Map.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code")).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("redirects are not rate limited: a popular link keeps working past the create limit")
    void redirectsAreNotThrottled() {
        String code = create("https://example.com/popular-" + UUID.randomUUID());

        for (int i = 0; i < 50; i++) {
            assertThat(rest.getForEntity(baseUrl() + "/" + code, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }
    }
}
