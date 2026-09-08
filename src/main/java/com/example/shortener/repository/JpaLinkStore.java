package com.example.shortener.repository;

import com.example.shortener.domain.Link;
import com.example.shortener.service.LinkStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class JpaLinkStore implements LinkStore {

    private final LinkRepository delegate;

    @Override
    public Optional<Link> findByShortCode(String shortCode) {
        return delegate.findByShortCode(shortCode);
    }

    @Override
    public Optional<Link> findByDedupKey(String dedupKey) {
        return delegate.findByDedupKey(dedupKey);
    }

    @Override
    public int insertIfAbsent(UUID id, String shortCode, String originalUrl, String fingerprint,
                              String dedupKey, String owner, Instant createdAt, Instant expiresAt) {
        return delegate.insertIfAbsent(id, shortCode, originalUrl, fingerprint, dedupKey, owner, createdAt, expiresAt);
    }

    @Override
    public Link save(Link link) {
        return delegate.save(link);
    }
}
