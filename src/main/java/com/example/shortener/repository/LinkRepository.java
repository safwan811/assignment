package com.example.shortener.repository;

import com.example.shortener.domain.Link;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LinkRepository extends JpaRepository<Link, UUID> {

    Optional<Link> findByShortCode(String shortCode);

    Optional<Link> findByDedupKey(String dedupKey);

    /**
     * Atomic conditional insert.
     *
     * ON CONFLICT DO NOTHING rather than catching DataIntegrityViolationException:
     * a constraint violation inside a transaction poisons it in PostgreSQL, so the
     * retry loop could not continue in the same transaction. Returns rows inserted.
     */
    @Modifying
    @Query(value = """
            INSERT INTO links (id, short_code, original_url, url_fingerprint, dedup_key, owner, status, created_at, expires_at)
            VALUES (:id, :shortCode, :originalUrl, :fingerprint, :dedupKey, :owner, 'ACTIVE', :createdAt, :expiresAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("shortCode") String shortCode,
                       @Param("originalUrl") String originalUrl,
                       @Param("fingerprint") String fingerprint,
                       @Param("dedupKey") String dedupKey,
                       @Param("owner") String owner,
                       @Param("createdAt") Instant createdAt,
                       @Param("expiresAt") Instant expiresAt);
}
