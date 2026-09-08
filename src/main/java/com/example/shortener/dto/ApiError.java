package com.example.shortener.dto;

import java.time.Instant;
import java.util.List;

/**
 * One error shape for every failure, so clients never have to branch on response format.
 * correlationId is echoed so a user-reported failure can be found in the logs.
 */
public record ApiError(
        String code,
        String message,
        List<String> details,
        String correlationId,
        Instant timestamp) {
}
