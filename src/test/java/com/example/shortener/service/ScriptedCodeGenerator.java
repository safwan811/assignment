package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Returns a predetermined sequence of codes so collision handling can be tested deterministically
 * instead of hoping SecureRandom repeats itself.
 */
class ScriptedCodeGenerator extends ShortCodeGenerator {

    private final Deque<String> scripted;

    ScriptedCodeGenerator(ShortenerProperties properties, String... codes) {
        super(properties);
        this.scripted = new ArrayDeque<>(List.of(codes));
    }

    @Override
    public String generate() {
        String next = scripted.poll();
        if (next == null) {
            throw new IllegalStateException("ScriptedCodeGenerator ran out of codes");
        }
        return next;
    }
}
