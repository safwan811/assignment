package com.example.shortener.web;

import com.example.shortener.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import com.example.shortener.dto.ApiError;

/**
 * Rate limits link creation only.
 *
 * Redirects are deliberately not limited: they are cheap, cached, and limiting them would
 * penalise a legitimately popular link. Abuse of the redirect path is handled by disabling
 * the link, not by throttling its visitors.
 */
@RequiredArgsConstructor
@Component
@Order(3)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/links"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (rateLimiter.tryAcquire(ClientIdentity.of(request))) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = new ApiError(
                "RATE_LIMITED",
                "Too many link creation requests. Try again shortly.",
                List.of(),
                MDC.get(CorrelationIdFilter.MDC_KEY),
                Instant.now());
        mapper.writeValue(response.getOutputStream(), error);
    }
}
