package com.example.shortener.analytics;

import java.time.Instant;

/**
 * Domain event published on every served redirect.
 *
 * Carries only the coarse attributes we intend to keep: no raw IP, no full user agent.
 * Minimising at the point of capture is cheaper and safer than minimising at write time.
 */
public record ClickRecorded(
        String shortCode,
        Instant occurredAt,
        String referrerHost,
        String userAgentFamily) {
}
