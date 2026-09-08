package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Random Base62 codes from SecureRandom rather than a Base62-encoded database sequence.
 *
 * A sequence is collision-free and shorter, but it makes every other customer's links
 * enumerable, which is a real privacy problem for a shared shortener. Randomness costs us
 * a uniqueness check; that check is the database unique constraint, which we needed anyway
 * for correctness across multiple instances. See docs/adr/ADR-001.
 */
@Component
public class ShortCodeGenerator {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final int codeLength;

    public ShortCodeGenerator(ShortenerProperties properties) {
        this.codeLength = properties.codeLength();
    }

    public String generate() {
        char[] buffer = new char[codeLength];
        for (int i = 0; i < codeLength; i++) {
            buffer[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(buffer);
    }
}
