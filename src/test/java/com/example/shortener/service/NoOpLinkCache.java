package com.example.shortener.service;

import com.example.shortener.cache.CachedLink;
import com.example.shortener.cache.LinkCache;

import java.util.Optional;

/**
 * Stands in for Redis in unit tests. Always reports a miss, which is also exactly how the real
 * LinkCache behaves when Redis is unavailable, so these tests double as a check that the
 * service is correct in the degraded path.
 */
class NoOpLinkCache extends LinkCache {

    NoOpLinkCache() {
        super(null, null);
    }

    @Override
    public Optional<Optional<CachedLink>> get(String shortCode) {
        return Optional.empty();
    }

    @Override
    public void put(String shortCode, CachedLink link) {
    }

    @Override
    public void putNegative(String shortCode) {
    }

    @Override
    public void evict(String shortCode) {
    }
}
