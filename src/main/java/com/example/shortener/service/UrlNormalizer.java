package com.example.shortener.service;

import com.example.shortener.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Produces the canonical form of a URL.
 *
 * Deliberately conservative (see docs/adr/ADR-002): scheme and host are lowercased, default
 * ports and fragments are dropped, but the query string is PRESERVED. Query parameters carry
 * meaning in most real URLs, so stripping them would silently redirect users to the wrong page.
 * The cost is that two URLs differing only in tracking parameters dedupe to different links.
 */
@Component
public class UrlNormalizer {

    public String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw new BadRequestException("url is required");
        }

        URI uri;
        try {
            uri = new URI(input.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException("url is not a valid URI");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BadRequestException("Only http and https URLs are supported");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("url must contain a valid host");
        }

        try {
            host = IDN.toASCII(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("url host is not a valid domain name");
        }

        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }

        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }

        StringBuilder normalized = new StringBuilder()
                .append(scheme).append("://").append(host);
        if (port != -1) {
            normalized.append(':').append(port);
        }
        normalized.append(URI.create(path).normalize().getRawPath());

        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            normalized.append('?').append(query);
        }
        // Fragment is intentionally dropped: it is never sent to the server.

        return normalized.toString();
    }
}
