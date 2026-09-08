package com.example.shortener.web;

import com.example.shortener.analytics.ClickRecorded;
import com.example.shortener.service.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;

/**
 * The redirect path. Everything here is on the hot path, so it does the minimum:
 * resolve (usually from cache), publish an event, return a Location header.
 */
@RestController
public class RedirectController {

    private final LinkService links;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RedirectController(LinkService links, ApplicationEventPublisher events, Clock clock) {
        this.links = links;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 302, not 301. A permanent redirect would be cached by browsers and intermediaries, which
     * means we would stop seeing clicks (breaking analytics) and could not revoke a link that
     * a user has already visited. The cost is that we serve more redirect traffic ourselves.
     *
     * The path pattern is constrained so this mapping cannot shadow /api/** or /actuator/**.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,64}}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        LinkService.Resolved resolved = links.resolve(code);

        events.publishEvent(new ClickRecorded(
                code,
                clock.instant(),
                referrerHost(request.getHeader("Referer")),
                userAgentFamily(request.getHeader("User-Agent"))));

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(resolved.destination()))
                .build();
    }

    /**
     * Only the referrer host is kept. The full referrer URL can contain query parameters with
     * personal data, and we have no need for it.
     */
    static String referrerHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return "DIRECT";
        }
        try {
            String host = new URI(referer).getHost();
            return host == null ? "UNKNOWN" : host.toLowerCase();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return "UNKNOWN";
        }
    }

    /**
     * Coarse bucketing rather than storing the raw user agent string, which is both a
     * fingerprinting vector and unbounded in length.
     */
    static String userAgentFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "UNKNOWN";
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")) {
            return "Bot";
        }
        if (ua.contains("edg/")) {
            return "Edge";
        }
        if (ua.contains("chrome")) {
            return "Chrome";
        }
        if (ua.contains("firefox")) {
            return "Firefox";
        }
        if (ua.contains("safari")) {
            return "Safari";
        }
        return "Other";
    }
}
