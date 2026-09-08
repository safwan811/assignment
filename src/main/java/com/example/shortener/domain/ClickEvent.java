package com.example.shortener.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per redirect served.
 *
 * Storing raw events rather than only counters keeps time-bucketed and referrer queries
 * possible. Raw IP addresses are deliberately not persisted (see docs/adr/ADR-003).
 */
@Entity
@Table(name = "click_events")
public class ClickEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "short_code", nullable = false, length = 64)
    private String shortCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "referrer_host", nullable = false, length = 255)
    private String referrerHost;

    @Column(name = "user_agent_family", nullable = false, length = 32)
    private String userAgentFamily;

    protected ClickEvent() {
        // for JPA
    }

    public ClickEvent(UUID id, String shortCode, Instant occurredAt,
                      String referrerHost, String userAgentFamily) {
        this.id = id;
        this.shortCode = shortCode;
        this.occurredAt = occurredAt;
        this.referrerHost = referrerHost;
        this.userAgentFamily = userAgentFamily;
    }

    public UUID getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReferrerHost() {
        return referrerHost;
    }

    public String getUserAgentFamily() {
        return userAgentFamily;
    }
}
