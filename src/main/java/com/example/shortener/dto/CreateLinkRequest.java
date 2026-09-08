package com.example.shortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * @param url         the destination. Bean Validation only checks presence and size here;
 *                    semantic checks (scheme, host, private-address block) live in UrlValidator
 *                    so they are unit-testable without the web layer.
 * @param customAlias optional caller-chosen code
 * @param expiresAt   optional absolute UTC expiry. No implicit default: silently expiring
 *                    someone's links is worse than making them ask for it.
 * @param forceNew    bypass deduplication and mint a fresh code for the same URL
 */
public record CreateLinkRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Size(max = 64, message = "customAlias must be at most 64 characters")
        String customAlias,

        Instant expiresAt,

        Boolean forceNew) {

    public boolean hasAlias() {
        return customAlias != null && !customAlias.isBlank();
    }

    public boolean isForceNew() {
        return Boolean.TRUE.equals(forceNew);
    }
}
