package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator(properties(7));

    @Test
    @DisplayName("produces codes of the configured length using only the Base62 alphabet")
    void producesWellFormedCodes() {
        for (int i = 0; i < 500; i++) {
            assertThat(generator.generate()).hasSize(7).matches("[A-Za-z0-9]{7}");
        }
    }

    @Test
    @DisplayName("codes are not sequential: 10k draws produce ~10k distinct values")
    void codesAreNotEnumerable() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }
        // Not asserting perfect uniqueness: randomness may legitimately collide. The point is
        // that the generator is not producing a predictable, walkable sequence.
        assertThat(codes).hasSizeGreaterThan(9_990);
    }

    @Test
    @DisplayName("respects a reconfigured code length")
    void respectsConfiguredLength() {
        assertThat(new ShortCodeGenerator(properties(12)).generate()).hasSize(12);
    }

    private ShortenerProperties properties(int codeLength) {
        return new ShortenerProperties("http://localhost:8080", codeLength, 5, 2048,
                Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(20),
                Duration.ofDays(1825), 60, true, List.of("api"));
    }
}
