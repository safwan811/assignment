package com.example.shortener.service;

import com.example.shortener.cache.CachedLink;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.dto.CreateLinkRequest;
import com.example.shortener.dto.LinkResponse;
import com.example.shortener.exception.BadRequestException;
import com.example.shortener.exception.ConflictException;
import com.example.shortener.exception.GoneException;
import com.example.shortener.exception.NotFoundException;
import com.example.shortener.exception.ServiceUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String OWNER = "ip:198.51.100.7";

    private InMemoryLinkStore store;
    private Cache<String, Optional<CachedLink>> l1;
    private LinkService service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        store = new InMemoryLinkStore();
        l1 = Caffeine.newBuilder().build();
        clock = new MutableClock(NOW);
        ShortenerProperties properties = properties();
        service = new LinkService(
                store,
                new UrlNormalizer(),
                new UrlValidator(properties),
                new ShortCodeGenerator(properties),
                new NoOpLinkCache(),
                l1,
                properties,
                clock);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("returns a short code and echoes the normalised destination")
        void createsLink() {
            LinkResponse response = service.create(request("HTTPS://Example.com/a"), OWNER);

            assertThat(response.shortCode()).matches("[A-Za-z0-9]{7}");
            assertThat(response.originalUrl()).isEqualTo("https://example.com/a");
            assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/" + response.shortCode());
            assertThat(response.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("is idempotent: the same URL from the same owner reuses the existing code")
        void deduplicatesPerOwner() {
            LinkResponse first = service.create(request("https://example.com/a"), OWNER);
            LinkResponse second = service.create(request("https://example.com/a"), OWNER);

            assertThat(second.shortCode()).isEqualTo(first.shortCode());
            assertThat(store.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("does not leak links across owners: a different owner gets its own code")
        void doesNotDeduplicateAcrossOwners() {
            LinkResponse mine = service.create(request("https://example.com/a"), OWNER);
            LinkResponse theirs = service.create(request("https://example.com/a"), "ip:203.0.113.9");

            assertThat(theirs.shortCode()).isNotEqualTo(mine.shortCode());
            assertThat(store.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("forceNew mints a fresh code for a URL that already has one")
        void forceNewBypassesDeduplication() {
            LinkResponse first = service.create(request("https://example.com/a"), OWNER);
            LinkResponse second = service.create(
                    new CreateLinkRequest("https://example.com/a", null, null, true), OWNER);

            assertThat(second.shortCode()).isNotEqualTo(first.shortCode());
        }

        @Test
        @DisplayName("retries past a code collision instead of failing the caller")
        void retriesOnCollision() {
            // First candidate is rejected by the store exactly as a unique-constraint violation
            // would reject it. The caller must still get a working link, transparently.
            LinkService withCollision = serviceWithCodes("taken1", "free01");
            store.poison("taken1");

            LinkResponse response = withCollision.create(request("https://example.com/a"), OWNER);
            assertThat(response.shortCode()).isEqualTo("free01");
        }

        @Test
        @DisplayName("gives up with 503 after the configured number of collisions, rather than looping")
        void failsClosedAfterRepeatedCollisions() {
            LinkService alwaysColliding = serviceWithCodes("c1", "c2", "c3", "c4", "c5");
            for (String code : new String[]{"c1", "c2", "c3", "c4", "c5"}) {
                store.poison(code);
            }

            assertThatThrownBy(() -> alwaysColliding.create(request("https://example.com/a"), OWNER))
                    .isInstanceOf(ServiceUnavailableException.class);
        }

        @Test
        @DisplayName("honours a valid custom alias")
        void acceptsCustomAlias() {
            LinkResponse response = service.create(
                    new CreateLinkRequest("https://example.com/a", "my-link", null, false), OWNER);
            assertThat(response.shortCode()).isEqualTo("my-link");
        }

        @Test
        @DisplayName("rejects a taken alias with 409 rather than silently returning another code")
        void rejectsTakenAlias() {
            service.create(new CreateLinkRequest("https://example.com/a", "taken", null, false), OWNER);

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/b", "taken", null, false), OWNER))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("rejects reserved aliases that would shadow real routes")
        void rejectsReservedAlias() {
            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/a", "api", null, false), OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("reserved");
        }

        @Test
        @DisplayName("rejects an expiry in the past")
        void rejectsPastExpiry() {
            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/a", null, NOW.minusSeconds(1), false), OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("future");
        }

        @Test
        @DisplayName("rejects an expiry beyond the configured maximum lifetime")
        void rejectsExcessiveExpiry() {
            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/a", null, NOW.plus(Duration.ofDays(4000)), false), OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maximum");
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("resolves an active link to its destination")
        void resolvesActiveLink() {
            String code = service.create(request("https://example.com/a"), OWNER).shortCode();
            assertThat(service.resolve(code).destination()).isEqualTo("https://example.com/a");
        }

        @Test
        @DisplayName("404s for a code that was never issued")
        void notFoundForUnknownCode() {
            assertThatThrownBy(() -> service.resolve("nosuch"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("410s once the link has expired, distinguishing it from a bad code")
        void goneAfterExpiry() {
            String code = service.create(
                    new CreateLinkRequest("https://example.com/a", null, NOW.plusSeconds(60), false), OWNER)
                    .shortCode();

            assertThat(service.resolve(code).destination()).isEqualTo("https://example.com/a");

            clock.advance(Duration.ofSeconds(61));
            assertThatThrownBy(() -> service.resolve(code))
                    .isInstanceOf(GoneException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("410s after the link is disabled, and the cached entry is invalidated")
        void goneAfterDisable() {
            String code = service.create(request("https://example.com/a"), OWNER).shortCode();
            service.resolve(code);            // warm L1
            service.disable(code);

            assertThatThrownBy(() -> service.resolve(code))
                    .isInstanceOf(GoneException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("disabling twice is a no-op, not an error")
        void disableIsIdempotent() {
            String code = service.create(request("https://example.com/a"), OWNER).shortCode();
            service.disable(code);
            service.disable(code);
            assertThatThrownBy(() -> service.resolve(code)).isInstanceOf(GoneException.class);
        }
    }

    private CreateLinkRequest request(String url) {
        return new CreateLinkRequest(url, null, null, false);
    }

    /** Builds a service whose generator emits the given codes in order. */
    private LinkService serviceWithCodes(String... codes) {
        ShortenerProperties properties = properties();
        return new LinkService(
                store,
                new UrlNormalizer(),
                new UrlValidator(properties),
                new ScriptedCodeGenerator(properties, codes),
                new NoOpLinkCache(),
                l1,
                properties,
                clock);
    }

    private ShortenerProperties properties() {
        return new ShortenerProperties("http://localhost:8080", 7, 5, 2048,
                Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(20),
                Duration.ofDays(1825), 60, true, List.of("api", "actuator", "health"));
    }

    /** Controllable clock so expiry can be tested without sleeping. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
