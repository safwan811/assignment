package com.example.shortener.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A short link.
 *
 * Invariants enforced at the database level, not in application code, because multiple
 * instances do not share memory:
 *  - short_code is unique
 *  - dedup_key is unique, which is what makes creation idempotent per owner
 *
 * dedup_key rather than (url_fingerprint, owner) directly: a caller that explicitly asks for a
 * fresh link (forceNew) needs to opt out of deduplication, and it does so by receiving a unique
 * dedup key. The uniqueness invariant therefore stays in the database for every code path.
 */
@Entity
@Table(name = "links")
public class Link {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "short_code", nullable = false, unique = true, length = 64)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    /** SHA-256 of the normalised URL. Indexed for dedup lookups without a 2 KB index key. */
    @Column(name = "url_fingerprint", nullable = false, length = 64)
    private String urlFingerprint;

    /** Uniqueness key for deduplication. See the class comment. */
    @Column(name = "dedup_key", nullable = false, unique = true, length = 200)
    private String dedupKey;

    @Column(name = "owner", nullable = false, length = 128)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LinkStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected Link() {
        // for JPA
    }

    public Link(UUID id, String shortCode, String originalUrl, String urlFingerprint, String dedupKey,
                String owner, LinkStatus status, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.urlFingerprint = urlFingerprint;
        this.dedupKey = dedupKey;
        this.owner = owner;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getUrlFingerprint() {
        return urlFingerprint;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public String getOwner() {
        return owner;
    }

    public LinkStatus getStatus() {
        return status;
    }

    public void setStatus(LinkStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
