package com.example.shortener.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP behaviour against real PostgreSQL and Redis: create, redirect, disable.
 */
class LinkLifecycleIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.registerPostgres(registry);
        Containers.registerRedis(registry);
        // Keep the rate limit out of the way; RateLimitIT covers the limit itself.
        registry.add("shortener.create-requests-per-minute", () -> 100_000);
    }

    @Test
    @DisplayName("create then redirect returns 302 to the original destination")
    void createThenRedirect() {
        String code = create("https://example.com/landing?id=7");

        ResponseEntity<Void> redirect = rest.exchange(
                RequestEntity.get(baseUrl() + "/" + code).build(), Void.class);

        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/landing?id=7");
    }

    @Test
    @DisplayName("the query string survives shortening: the destination is not truncated")
    void preservesQueryString() {
        String code = create("https://shop.example.com/item?sku=ABC123&size=M");
        ResponseEntity<Void> redirect = rest.exchange(
                RequestEntity.get(baseUrl() + "/" + code).build(), Void.class);

        assertThat(redirect.getHeaders().getLocation())
                .hasToString("https://shop.example.com/item?sku=ABC123&size=M");
    }

    @Test
    @DisplayName("creating the same URL twice from the same caller returns the same code")
    void deduplicates() {
        assertThat(create("https://example.com/same")).isEqualTo(create("https://example.com/same"));
    }

    @Test
    @DisplayName("forceNew produces a second, distinct code for the same URL")
    void forceNewCreatesDistinctCode() {
        String first = create("https://example.com/same");

        ResponseEntity<Map> second = rest.postForEntity(
                baseUrl() + "/api/v1/links",
                Map.of("url", "https://example.com/same", "forceNew", true),
                Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("shortCode")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("an unknown code returns 404 with the standard error body")
    void unknownCodeIsNotFound() {
        ResponseEntity<Map> response = rest.getForEntity(baseUrl() + "/zzzzzzz", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
        assertThat(response.getBody()).containsKey("correlationId");
    }

    @Test
    @DisplayName("a disabled link returns 410, and stays disabled after the cache is evicted")
    void disabledLinkReturnsGone() {
        String code = create("https://example.com/temporary");
        rest.exchange(RequestEntity.get(baseUrl() + "/" + code).build(), Void.class); // warm cache

        rest.delete(baseUrl() + "/api/v1/links/" + code);

        ResponseEntity<Map> response = rest.getForEntity(baseUrl() + "/" + code, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().get("code")).isEqualTo("LINK_GONE");
    }

    @Test
    @DisplayName("rejects a destination pointing at cloud instance metadata")
    void rejectsSsrfTarget() {
        ResponseEntity<Map> response = rest.postForEntity(
                baseUrl() + "/api/v1/links",
                Map.of("url", "http://169.254.169.254/latest/meta-data/"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("a custom alias that collides returns 409")
    void aliasCollisionReturnsConflict() {
        rest.postForEntity(baseUrl() + "/api/v1/links",
                Map.of("url", "https://example.com/a", "customAlias", "promo"), Map.class);

        ResponseEntity<Map> second = rest.postForEntity(baseUrl() + "/api/v1/links",
                Map.of("url", "https://example.com/b", "customAlias", "promo"), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a reserved alias cannot shadow the API routes")
    void reservedAliasRejected() {
        ResponseEntity<Map> response = rest.postForEntity(baseUrl() + "/api/v1/links",
                Map.of("url", "https://example.com/a", "customAlias", "actuator"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("every response carries a correlation id header for log lookup")
    void returnsCorrelationId() {
        ResponseEntity<Map> response = rest.postForEntity(baseUrl() + "/api/v1/links",
                Map.of("url", "https://example.com/a"), Map.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

}
