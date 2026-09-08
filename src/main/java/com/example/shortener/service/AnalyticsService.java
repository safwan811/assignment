package com.example.shortener.service;

import com.example.shortener.dto.StatsResponse;
import com.example.shortener.repository.ClickEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class AnalyticsService {

    private static final int DEFAULT_DAYS = 30;
    private static final int TOP_REFERRERS = 10;

    private final ClickEventRepository clicks;
    private final LinkService links;
    private final Clock clock;

    /**
     * Existence is checked first so that stats for an unknown code return 404 rather than
     * a plausible-looking response full of zeroes.
     */
    @Transactional(readOnly = true)
    public StatsResponse statsFor(String shortCode) {
        links.requireExisting(shortCode);

        Instant since = clock.instant().minus(Duration.ofDays(DEFAULT_DAYS));

        List<StatsResponse.DailyCount> byDay = clicks.countByDay(shortCode, since).stream()
                .map(row -> new StatsResponse.DailyCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        List<StatsResponse.ReferrerCount> referrers = clicks.topReferrers(shortCode, TOP_REFERRERS).stream()
                .map(row -> new StatsResponse.ReferrerCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        Map<String, Long> byAgent = new LinkedHashMap<>();
        for (Object[] row : clicks.countByUserAgentFamily(shortCode)) {
            byAgent.put((String) row[0], ((Number) row[1]).longValue());
        }

        return new StatsResponse(shortCode, clicks.countByShortCode(shortCode), byDay, referrers, byAgent);
    }
}
