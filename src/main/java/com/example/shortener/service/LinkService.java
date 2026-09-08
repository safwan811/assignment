package com.example.shortener.service;

import com.example.shortener.cache.CachedLink;
import com.example.shortener.cache.LinkCache;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.Link;
import com.example.shortener.domain.LinkStatus;
import com.example.shortener.dto.CreateLinkRequest;
import com.example.shortener.dto.LinkResponse;
import com.example.shortener.exception.BadRequestException;
import com.example.shortener.exception.ConflictException;
import com.example.shortener.exception.GoneException;
import com.example.shortener.exception.NotFoundException;
import com.example.shortener.exception.ServiceUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core write and resolve logic.
 *
 * Correctness rule that shapes this whole class: multiple instances do not share memory, so
 * uniqueness is enforced by database constraints, never by JVM locks. Every "check then insert"
 * is expressed as a conditional insert plus a re-read of the winner.
 */
@Service
public class LinkService {

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    private final LinkStore repository;
    private final UrlNormalizer normalizer;
    private final UrlValidator validator;
    private final ShortCodeGenerator generator;
    private final LinkCache l2;
    private final Cache<String, Optional<CachedLink>> l1;
    private final ShortenerProperties properties;
    private final Clock clock;

    public LinkService(LinkStore repository,
                       UrlNormalizer normalizer,
                       UrlValidator validator,
                       ShortCodeGenerator generator,
                       LinkCache l2,
                       Cache<String, Optional<CachedLink>> l1,
                       ShortenerProperties properties,
                       Clock clock) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.validator = validator;
        this.generator = generator;
        this.l2 = l2;
        this.l1 = l1;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public LinkResponse create(CreateLinkRequest request, String owner) {
        String normalized = normalizer.normalize(request.url());
        validator.validate(normalized);

        Instant expiresAt = validateExpiry(request.expiresAt());
        String fingerprint = Hashing.sha256Hex(normalized);

        // Deduplication is scoped to the owner: two tenants shortening the same URL must not be
        // able to observe each other's links or each other's analytics. A caller that explicitly
        // wants a fresh link opts out of this pre-check by setting forceNew.
        boolean deduplicate = !request.isForceNew() && !request.hasAlias();
        if (deduplicate) {
            Optional<Link> existing = repository.findByFingerprintAndOwner(fingerprint, owner);
            if (existing.isPresent() && existing.get().getStatus() == LinkStatus.ACTIVE) {
                return toResponse(existing.get());
            }
        }

        if (request.hasAlias()) {
            validateAlias(request.customAlias());
            return createWithCode(request.customAlias(), normalized, fingerprint, owner, expiresAt, true);
        }

        for (int attempt = 0; attempt < properties.maxCollisionRetries(); attempt++) {
            try {
                return createWithCode(generator.generate(), normalized, fingerprint, owner, expiresAt, false);
            } catch (CodeTakenException e) {
                // A generated code collided. Try another candidate; this is expected and rare.
            }
        }
        throw new ServiceUnavailableException(
                "Could not allocate a unique short code after " + properties.maxCollisionRetries() + " attempts");
    }

    private LinkResponse createWithCode(String code, String normalizedUrl, String fingerprint,
                                        String owner, Instant expiresAt, boolean aliasRequested) {
        UUID id = UUID.randomUUID();
        Instant createdAt = clock.instant();

        int inserted = repository.insertIfAbsent(id, code, normalizedUrl, fingerprint, owner, createdAt, expiresAt);
        if (inserted == 1) {
            Link link = repository.findByShortCode(code).orElseThrow();
            cache(link);
            return toResponse(link);
        }

        // The insert lost a race, or the code/fingerprint already exists. Work out which.
        Optional<Link> byCode = repository.findByShortCode(code);
        if (aliasRequested && byCode.isPresent()) {
            throw new ConflictException("customAlias is already in use");
        }
        Optional<Link> byFingerprint = repository.findByFingerprintAndOwner(fingerprint, owner);
        if (byFingerprint.isPresent()) {
            // A concurrent request created the same URL for the same owner first. Its result is
            // just as valid as ours, so return it instead of failing the caller.
            return toResponse(byFingerprint.get());
        }
        throw new CodeTakenException();
    }

    // ----------------------------------------------------------------- resolve

    /**
     * Redirect path. L1 (pod-local) then L2 (Redis) then PostgreSQL.
     *
     * Not annotated @Transactional: the common case never touches the database, and opening a
     * transaction per redirect would burn a connection from the pool for a cache hit.
     */
    public Resolved resolve(String shortCode) {
        Optional<CachedLink> cached = l1.get(shortCode, this::load);
        if (cached.isEmpty()) {
            throw new NotFoundException("Short code not found");
        }
        CachedLink link = cached.get();

        if (!LinkStatus.ACTIVE.name().equals(link.status())) {
            throw new GoneException("This link has been disabled");
        }
        if (link.expiresAt() != null && !link.expiresAt().isAfter(clock.instant())) {
            throw new GoneException("This link has expired");
        }
        return new Resolved(shortCode, link.originalUrl());
    }

    /**
     * Caffeine computes this at most once per key per pod, which collapses a burst of misses on
     * a hot code into a single database read instead of one per request.
     */
    private Optional<CachedLink> load(String shortCode) {
        Optional<Optional<CachedLink>> fromRedis = l2.get(shortCode);
        if (fromRedis.isPresent()) {
            return fromRedis.get();
        }
        Optional<Link> fromDb = repository.findByShortCode(shortCode);
        if (fromDb.isEmpty()) {
            l2.putNegative(shortCode);
            return Optional.empty();
        }
        CachedLink cached = toCached(fromDb.get());
        l2.put(shortCode, cached);
        return Optional.of(cached);
    }

    // ------------------------------------------------------------------ read / disable

    @Transactional(readOnly = true)
    public LinkResponse get(String shortCode) {
        return toResponse(requireExisting(shortCode));
    }

    @Transactional(readOnly = true)
    public Link requireExisting(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new NotFoundException("Short code not found"));
    }

    /**
     * Soft delete. Codes are never reused, so a disabled link returns 410 forever rather than
     * being silently reassigned to a different destination later.
     */
    @Transactional
    public void disable(String shortCode) {
        Link link = requireExisting(shortCode);
        if (link.getStatus() != LinkStatus.DISABLED) {
            link.setStatus(LinkStatus.DISABLED);
            repository.save(link);
        }
        // Evict both tiers. L1 is per-pod, so other pods keep serving until their TTL expires;
        // that window is bounded by shortener.l1-ttl and is documented in docs/RISKS.md.
        l1.invalidate(shortCode);
        l2.evict(shortCode);
    }

    // ------------------------------------------------------------------ helpers

    private Instant validateExpiry(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            throw new BadRequestException("expiresAt must be in the future");
        }
        if (expiresAt.isAfter(now.plus(properties.maxExpiry()))) {
            throw new BadRequestException("expiresAt exceeds the maximum allowed lifetime");
        }
        return expiresAt;
    }

    private void validateAlias(String alias) {
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new BadRequestException("customAlias must match " + ALIAS_PATTERN.pattern());
        }
        boolean reserved = properties.reservedAliases().stream().anyMatch(r -> r.equalsIgnoreCase(alias));
        if (reserved) {
            throw new BadRequestException("customAlias is reserved");
        }
    }

    private void cache(Link link) {
        CachedLink cached = toCached(link);
        l1.put(link.getShortCode(), Optional.of(cached));
        l2.put(link.getShortCode(), cached);
    }

    private CachedLink toCached(Link link) {
        return new CachedLink(link.getOriginalUrl(), link.getStatus().name(), link.getExpiresAt());
    }

    private LinkResponse toResponse(Link link) {
        return new LinkResponse(
                link.getShortCode(),
                properties.baseUrl() + "/" + link.getShortCode(),
                link.getOriginalUrl(),
                link.getStatus().name(),
                link.getCreatedAt(),
                link.getExpiresAt());
    }

    public record Resolved(String shortCode, String destination) {
    }

    /** Internal control-flow signal for the collision retry loop; never leaves this class. */
    static class CodeTakenException extends RuntimeException {
        CodeTakenException() {
            super(null, null, false, false);
        }
    }
}
