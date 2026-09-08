package com.example.shortener.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim being tested: deduplication is enforced by a database constraint, so simultaneous
 * requests for the same URL converge on one link instead of racing to create several.
 *
 * This is the test that would fail if someone "simplified" the conditional insert into a
 * check-then-insert, which passes single-threaded tests and breaks in production.
 */
class ConcurrentCreationIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.registerPostgres(registry);
        Containers.registerRedis(registry);
        // Keep the rate limit out of the way; RateLimitIT covers the limit itself.
        registry.add("shortener.create-requests-per-minute", () -> 100_000);
    }

    @Test
    @DisplayName("20 concurrent creates of the same URL produce exactly one link")
    void concurrentCreatesConverge() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> codes = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                ResponseEntity<Map> response = rest.postForEntity(
                        baseUrl() + "/api/v1/links",
                        Map.of("url", "https://example.com/contended"),
                        Map.class);
                if (response.getBody() != null && response.getBody().get("shortCode") != null) {
                    codes.add((String) response.getBody().get("shortCode"));
                }
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(codes).hasSize(1);
        assertThat(linkRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent creates of the same custom alias: exactly one wins, the rest get 409")
    void concurrentAliasCreatesProduceOneWinner() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> statuses = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                start.await();
                ResponseEntity<Map> response = rest.postForEntity(
                        baseUrl() + "/api/v1/links",
                        Map.of("url", "https://example.com/p" + n, "customAlias", "contended"),
                        Map.class);
                statuses.add(response.getStatusCode().value());
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(linkRepository.findByShortCode("contended")).isPresent();
        assertThat(linkRepository.count()).isEqualTo(1);
        assertThat(statuses).contains(201, 409);
    }
}
