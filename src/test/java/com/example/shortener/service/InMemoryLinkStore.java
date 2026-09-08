package com.example.shortener.service;

import com.example.shortener.domain.Link;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hand-written fake with the same uniqueness semantics as the database:
 * insertIfAbsent returns 0 if either the short code OR the (fingerprint, owner) pair is taken.
 *
 * Written by hand rather than mocked so the retry and race-resolution logic in LinkService is
 * exercised against realistic behaviour instead of against whatever a mock was told to return.
 */
class InMemoryLinkStore implements LinkStore {

    private final Map<String, Link> byCode = new LinkedHashMap<>();

    /** Codes that insertIfAbsent should reject once, to simulate a collision. */
    private final java.util.Set<String> poisonedCodes = new java.util.HashSet<>();

    void poison(String code) {
        poisonedCodes.add(code);
    }

    @Override
    public Optional<Link> findByShortCode(String shortCode) {
        return Optional.ofNullable(byCode.get(shortCode));
    }

    @Override
    public Optional<Link> findByFingerprintAndOwner(String fingerprint, String owner) {
        return byCode.values().stream()
                .filter(l -> l.getUrlFingerprint().equals(fingerprint) && l.getOwner().equals(owner))
                .findFirst();
    }

    @Override
    public int insertIfAbsent(UUID id, String shortCode, String originalUrl, String fingerprint,
                              String owner, Instant createdAt, Instant expiresAt) {
        if (poisonedCodes.remove(shortCode)) {
            return 0;
        }
        if (byCode.containsKey(shortCode)) {
            return 0;
        }
        if (findByFingerprintAndOwner(fingerprint, owner).isPresent()) {
            return 0;
        }
        byCode.put(shortCode, new Link(id, shortCode, originalUrl, fingerprint, owner,
                com.example.shortener.domain.LinkStatus.ACTIVE, createdAt, expiresAt));
        return 1;
    }

    @Override
    public Link save(Link link) {
        byCode.put(link.getShortCode(), link);
        return link;
    }

    int size() {
        return byCode.size();
    }
}
