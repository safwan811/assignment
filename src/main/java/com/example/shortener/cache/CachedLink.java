package com.example.shortener.cache;

import java.time.Instant;

/**
 * Cache projection of a Link: only what the redirect path needs. Kept separate from the JPA
 * entity so the cached payload does not change shape every time the entity gains a column.
 */
public record CachedLink(String originalUrl, String status, Instant expiresAt) {
}
