package com.example.shortener.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
 *
 * The rate-limit counter itself lives in the shared Redis container (Containers.REDIS), not in
 * this test's Spring context, and is keyed by client identity + time window. Every other IT class
 * creates links from the same test-JVM loopback address under the default 60/min limit, so without
 * an isolated identity here, an unlucky test order leaves this class's counter already exhausted
 * before its own assertions run. Each test method uses its own X-Api-Key so its bucket can never
 * collide with another class's creates or with the other test method in this class.
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
        HttpHeaders headers = apiKeyHeaders();
        // Unique suffix so a retry of this test in the same minute does not inherit the previous
        // window's counter for this client.
        String run = UUID.randomUUID().toString().substring(0, 8);

        for (int i = 0; i < LIMIT; i++) {
            ResponseEntity<Map> ok = rest.postForEntity(baseUrl() + "/api/v1/links",
                    new HttpEntity<>(Map.of("url", "https://example.com/" + run + "/" + i), headers), Map.class);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<Map> limited = rest.postForEntity(baseUrl() + "/api/v1/links",
                new HttpEntity<>(Map.of("url", "https://example.com/" + run + "/over"), headers), Map.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code")).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("redirects are not rate limited: a popular link keeps working past the create limit")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redirectsAreNotThrottled() {
        HttpHeaders headers = apiKeyHeaders();
        ResponseEntity<Map> created = rest.postForEntity(baseUrl() + "/api/v1/links",
                new HttpEntity<>(Map.of("url", "https://example.com/popular-" + UUID.randomUUID()), headers),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = (String) created.getBody().get("shortCode");

        for (int i = 0; i < 50; i++) {
            assertThat(rest.getForEntity(baseUrl() + "/" + code, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }
    }

    private static HttpHeaders apiKeyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Api-Key", "ratelimitit-" + UUID.randomUUID());
        return headers;
    }
}
