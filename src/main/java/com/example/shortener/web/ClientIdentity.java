package com.example.shortener.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Identifies the caller for rate limiting and link ownership.
 *
 * An API key header is honoured when present; otherwise we fall back to the remote address.
 * X-Forwarded-For is deliberately NOT trusted: it is trivially spoofed unless the service sits
 * behind a proxy that overwrites it, and trusting it would let one client bypass the rate limit
 * entirely. Behind a real load balancer this should be replaced by the proxy-set client IP.
 */
public final class ClientIdentity {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String ANONYMOUS = "anonymous";

    private ClientIdentity() {
    }

    public static String of(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey.substring(0, Math.min(apiKey.length(), 64));
        }
        String remote = request.getRemoteAddr();
        return remote == null ? ANONYMOUS : "ip:" + remote;
    }
}
