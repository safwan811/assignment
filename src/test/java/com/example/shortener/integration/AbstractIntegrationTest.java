package com.example.shortener.integration;

import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared plumbing for HTTP-level integration tests. Property registration is left to each
 * concrete test class so that a test which needs different configuration (a low rate limit,
 * an unreachable Redis) gets its own context instead of fighting an inherited override.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected LinkRepository linkRepository;

    @Autowired
    protected ClickEventRepository clickEventRepository;

    @BeforeEach
    void resetState() {
        clickEventRepository.deleteAll();
        linkRepository.deleteAll();
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected String create(String url) {
        var response = rest.postForEntity(baseUrl() + "/api/v1/links", Map.of("url", url), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) response.getBody().get("shortCode");
    }
}
