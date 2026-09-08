package com.example.shortener.dto;

import java.time.Instant;

public record LinkResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        String status,
        Instant createdAt,
        Instant expiresAt) {
}
