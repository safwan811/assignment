package com.example.shortener.dto;

import java.util.List;
import java.util.Map;

public record StatsResponse(
        String shortCode,
        long totalClicks,
        List<DailyCount> clicksByDay,
        List<ReferrerCount> topReferrers,
        Map<String, Long> byUserAgentFamily) {

    public record DailyCount(String day, long clicks) {
    }

    public record ReferrerCount(String referrerHost, long clicks) {
    }
}
