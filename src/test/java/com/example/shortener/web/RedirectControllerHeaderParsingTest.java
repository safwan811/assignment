package com.example.shortener.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Header parsing is pure logic, so it is tested directly rather than through the web layer.
 * These are the functions that decide what we store about a visitor, so the privacy-relevant
 * behaviour (host only, coarse agent family) is asserted explicitly.
 */
class RedirectControllerHeaderParsingTest {

    @ParameterizedTest
    @CsvSource({
            "https://news.example.com/article?user=alice, news.example.com",
            "https://News.Example.com/,                   news.example.com",
            "not-a-url,                                   UNKNOWN"
    })
    @DisplayName("keeps only the referrer host, discarding any path or query")
    void extractsReferrerHost(String referer, String expected) {
        assertThat(RedirectController.referrerHost(referer)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("a missing referrer is recorded as DIRECT, not as unknown")
    void missingRefererIsDirect(String referer) {
        assertThat(RedirectController.referrerHost(referer)).isEqualTo("DIRECT");
    }

    @ParameterizedTest
    @CsvSource({
            "'Mozilla/5.0 (Windows) Chrome/120.0',                        Chrome",
            "'Mozilla/5.0 Chrome/120 Edg/120.0',                          Edge",
            "'Mozilla/5.0 (Macintosh) Version/17 Safari/605.1',           Safari",
            "'Mozilla/5.0 Gecko/20100101 Firefox/121.0',                  Firefox",
            "'Googlebot/2.1 (+http://www.google.com/bot.html)',           Bot",
            "'curl/8.4.0',                                                Other"
    })
    @DisplayName("buckets user agents coarsely instead of storing the raw string")
    void bucketsUserAgents(String userAgent, String expected) {
        assertThat(RedirectController.userAgentFamily(userAgent)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Edge is not misreported as Chrome, even though it claims to be Chrome")
    void edgeBeatsChrome() {
        assertThat(RedirectController.userAgentFamily("Chrome/120 Edg/120")).isEqualTo("Edge");
    }
}
