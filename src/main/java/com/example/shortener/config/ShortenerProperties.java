package com.example.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Externalised tuning knobs. Every default here is a deliberate engineering choice;
 * see docs/adr for the reasoning.
 */
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        String baseUrl,
        int codeLength,
        int maxCollisionRetries,
        int maxUrlLength,
        Duration l1Ttl,
        Duration redisTtl,
        Duration negativeCacheTtl,
        Duration maxExpiry,
        int createRequestsPerMinute,
        boolean blockPrivateAddresses,
        List<String> reservedAliases) {
}
