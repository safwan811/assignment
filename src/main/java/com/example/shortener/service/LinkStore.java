package com.example.shortener.service;

import com.example.shortener.domain.Link;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence operations LinkService actually needs, and nothing else.
 *
 * This seam exists so the service can be unit tested against a small in-memory fake instead of
 * a mock of a 20-method JpaRepository. It also documents the real coupling to the database:
 * a conditional insert plus two lookups.
 */
public interface LinkStore {

    Optional<Link> findByShortCode(String shortCode);

    Optional<Link> findByDedupKey(String dedupKey);

    /** @return 1 if the row was inserted, 0 if a uniqueness conflict meant it was not. */
    int insertIfAbsent(UUID id, String shortCode, String originalUrl, String fingerprint,
                       String dedupKey, String owner, Instant createdAt, Instant expiresAt);

    Link save(Link link);
}
