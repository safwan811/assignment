package com.example.shortener.ratelimit;

import com.example.shortener.config.ShortenerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Fixed-window counter in Redis, shared across instances.
 *
 * Two decisions worth defending:
 *  - Fixed window, not a sliding window or token bucket. It allows up to 2x the limit across
 *    a window boundary. For abuse control on link creation that is acceptable, and it costs
 *    one INCR instead of a Lua script.
 *  - FAIL OPEN when Redis is unavailable. Creation is not a security boundary; making the
 *    whole write path depend on Redis availability would turn a cache outage into an outage.
 *    If this were login or payments the answer would be the opposite. See docs/RISKS.md.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final ShortenerProperties properties;
    private final Clock clock;

    public boolean tryAcquire(String clientId) {
        long window = clock.instant().getEpochSecond() / 60;
        String key = "rl:create:" + clientId + ":" + window;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(120));
            }
            return count == null || count <= properties.createRequestsPerMinute();
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, failing open for {}: {}", clientId, e.toString());
            return true;
        }
    }
}
