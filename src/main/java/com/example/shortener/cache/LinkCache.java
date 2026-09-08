package com.example.shortener.cache;

import com.example.shortener.config.ShortenerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * L2 cache (Redis), shared across instances.
 *
 * Every method swallows Redis failures and returns "not cached". That is the graceful
 * degradation requirement: if Redis is down, redirects get slower (they fall through to
 * PostgreSQL) but they keep working. Redis is never the source of truth.
 *
 * Production hardening not implemented here: a circuit breaker so that a hard-down Redis
 * does not add its connect timeout to every request. Noted in docs/RISKS.md.
 */
@Component
public class LinkCache {

    private static final Logger log = LoggerFactory.getLogger(LinkCache.class);
    private static final String NEGATIVE = "__NOT_FOUND__";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ShortenerProperties properties;

    /**
     * Builds its own ObjectMapper rather than taking one as a Spring bean: exposing a bare
     * {@code @Bean ObjectMapper} makes Spring Boot's auto-configuration back off from creating
     * its own (it only creates one if none exists), so this cache's serialisation settings would
     * silently leak into every JSON HTTP response, including overriding
     * spring.jackson.serialization.write-dates-as-timestamps.
     */
    public LinkCache(StringRedisTemplate redis, ShortenerProperties properties) {
        this.redis = redis;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.properties = properties;
    }

    /**
     * @return empty  => nothing cached, caller must consult the database
     *         present with empty inner Optional => negatively cached, the code does not exist
     */
    public Optional<Optional<CachedLink>> get(String shortCode) {
        try {
            String raw = redis.opsForValue().get(key(shortCode));
            if (raw == null) {
                return Optional.empty();
            }
            if (NEGATIVE.equals(raw)) {
                return Optional.of(Optional.empty());
            }
            return Optional.of(Optional.of(mapper.readValue(raw, CachedLink.class)));
        } catch (Exception e) {
            log.warn("Redis read failed for {}, falling back to database: {}", shortCode, e.toString());
            return Optional.empty();
        }
    }

    public void put(String shortCode, CachedLink link) {
        try {
            redis.opsForValue().set(key(shortCode), mapper.writeValueAsString(link), properties.redisTtl());
        } catch (Exception e) {
            log.warn("Redis write failed for {}: {}", shortCode, e.toString());
        }
    }

    /**
     * Negative caching with a deliberately short TTL. It absorbs scanning traffic for
     * non-existent codes, but must expire quickly so a newly created code is not shadowed.
     */
    public void putNegative(String shortCode) {
        try {
            redis.opsForValue().set(key(shortCode), NEGATIVE, properties.negativeCacheTtl());
        } catch (Exception e) {
            log.warn("Redis negative-cache write failed for {}: {}", shortCode, e.toString());
        }
    }

    public void evict(String shortCode) {
        try {
            redis.delete(key(shortCode));
        } catch (Exception e) {
            // Logged at WARN because a failed eviction means a disabled link may keep
            // redirecting until the TTL expires. Bounded by redisTtl, but worth alerting on.
            log.warn("Redis eviction failed for {}: {}", shortCode, e.toString());
        }
    }

    private String key(String shortCode) {
        return "link:" + shortCode;
    }
}
