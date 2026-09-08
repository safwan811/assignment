package com.example.shortener.config;

import com.example.shortener.cache.CachedLink;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;

@Configuration
public class AppConfig {

    /**
     * Injected everywhere instead of calling Instant.now() directly, so that
     * time-dependent behaviour (expiry, rate-limit windows) is deterministically testable.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * L1 (pod-local) cache. Optional.empty() is the negative-cache sentinel.
     * Short TTL on purpose: a stale local entry is acceptable for redirects, but must not
     * long outlive an operator disabling an abusive link.
     */
    @Bean
    public Cache<String, Optional<CachedLink>> l1Cache(ShortenerProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(properties.l1Ttl())
                .recordStats()
                .build();
    }
}
