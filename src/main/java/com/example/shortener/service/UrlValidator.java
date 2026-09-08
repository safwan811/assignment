package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Abuse and SSRF guardrails applied at creation time.
 *
 * Known limitation, stated rather than hidden: we only reject hosts that are IP literals in a
 * private range, or well-known loopback names. We do NOT resolve DNS here, because doing so
 * (a) adds an uncontrolled network call to the create path, and (b) is defeated by DNS rebinding
 * anyway. The durable control is that this service never fetches the target URL itself; see
 * docs/RISKS.md.
 */
@RequiredArgsConstructor
@Component
public class UrlValidator {

    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "localhost.localdomain", "ip6-localhost");

    private final ShortenerProperties properties;

    public void validate(String normalizedUrl) {
        if (normalizedUrl.length() > properties.maxUrlLength()) {
            throw new BadRequestException("url exceeds the maximum length of " + properties.maxUrlLength());
        }
        if (!properties.blockPrivateAddresses()) {
            return;
        }

        String host = URI.create(normalizedUrl).getHost();
        if (host == null) {
            throw new BadRequestException("url must contain a valid host");
        }
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;

        if (LOOPBACK_NAMES.contains(bare.toLowerCase())) {
            throw new BadRequestException("url must not point at a loopback address");
        }
        if (!isIpLiteral(bare)) {
            return;
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(bare);
        } catch (UnknownHostException e) {
            throw new BadRequestException("url host is not a valid address");
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            throw new BadRequestException("url must not point at a private, loopback or link-local address");
        }
    }

    /**
     * True only for textual IP addresses. Guards against InetAddress.getByName performing a
     * DNS lookup for hostnames, which we explicitly do not want on the create path.
     */
    private boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
