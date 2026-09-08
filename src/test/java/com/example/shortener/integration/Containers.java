package com.example.shortener.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared containers for the whole integration test run.
 *
 * Real PostgreSQL and real Redis, not H2 and not an embedded fake. The behaviour this service
 * depends on - ON CONFLICT DO NOTHING, TIMESTAMPTZ, date_trunc, Redis TTL - is exactly what an
 * in-memory substitute gets subtly wrong, which would make the suite reassuring and useless.
 *
 * Started once in a static initialiser and reused, so the suite pays container startup cost once.
 */
final class Containers {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("shortener")
                    .withUsername("shortener")
                    .withPassword("shortener");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private Containers() {
    }

    static void registerPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
