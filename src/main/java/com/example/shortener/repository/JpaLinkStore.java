package com.example.shortener.repository;

import com.example.shortener.domain.Link;
import com.example.shortener.service.LinkStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class JpaLinkStore implements LinkStore {

    private final LinkRepository delegate;

    public JpaLinkStore(LinkRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<Link> findByShortCode(String shortCode) {
        return delegate.findByShortCode(shortCode);
    }

    @Override
    public Optional<Link> findByFingerprintAndOwner(String fingerprint, String owner) {
        return delegate.findByUrlFingerprintAndOwner(fingerprint, owner);
    }

    @Override
    public int insertIfAbsent(UUID id, String shortCode, String originalUrl, String fingerprint,
                              String owner, Instant createdAt, Instant expiresAt) {
        return delegate.insertIfAbsent(id, shortCode, originalUrl, fingerprint, owner, createdAt, expiresAt);
    }

    @Override
    public Link save(Link link) {
        return delegate.save(link);
    }
}
