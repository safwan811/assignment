package com.example.shortener.integration;

import com.example.shortener.dto.StatsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Analytics is written asynchronously, so these tests poll rather than assert immediately.
 * That is not a workaround; it is the actual contract, and asserting it synchronously would
 * hide a regression where the write accidentally moved back onto the request thread.
 */
class AnalyticsIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.registerPostgres(registry);
        Containers.registerRedis(registry);
        // Keep the rate limit out of the way; RateLimitIT covers the limit itself.
        registry.add("shortener.create-requests-per-minute", () -> 100_000);
    }

    @Test
    @DisplayName("each redirect is eventually counted, with referrer and agent attributed")
    void recordsClicks() {
        String code = create("https://example.com/campaign");

        visit(code, "https://news.example.com/story", "Mozilla/5.0 Chrome/120.0");
        visit(code, "https://news.example.com/other", "Mozilla/5.0 Chrome/120.0");
        visit(code, null, "Googlebot/2.1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            StatsResponse stats = rest.getForObject(
                    baseUrl() + "/api/v1/links/" + code + "/stats", StatsResponse.class);

            assertThat(stats.totalClicks()).isEqualTo(3);
            assertThat(stats.byUserAgentFamily()).containsEntry("Chrome", 2L).containsEntry("Bot", 1L);
            assertThat(stats.topReferrers())
                    .anySatisfy(r -> {
                        assertThat(r.referrerHost()).isEqualTo("news.example.com");
                        assertThat(r.clicks()).isEqualTo(2);
                    })
                    .anySatisfy(r -> assertThat(r.referrerHost()).isEqualTo("DIRECT"));
            assertThat(stats.clicksByDay()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("analytics failures never break the redirect: a redirect works before any stats exist")
    void redirectDoesNotDependOnAnalytics() {
        String code = create("https://example.com/fast");
        assertThat(rest.getForEntity(baseUrl() + "/" + code, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);
    }

    @Test
    @DisplayName("stats for an unknown code are 404, not a plausible-looking page of zeroes")
    void statsForUnknownCodeAreNotFound() {
        ResponseEntity<Map> response =
                rest.getForEntity(baseUrl() + "/api/v1/links/nosuchcode/stats", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void visit(String code, String referer, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        if (referer != null) {
            headers.set("Referer", referer);
        }
        headers.set("User-Agent", userAgent);
        rest.exchange(baseUrl() + "/" + code, HttpMethod.GET, new HttpEntity<>(headers), Void.class);
    }

}
