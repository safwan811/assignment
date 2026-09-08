package com.example.shortener.analytics;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Writes click events off the request thread.
 *
 * The redirect must not wait for, or fail because of, an analytics write. Failures are logged
 * and dropped on purpose: an under-counted dashboard is a far smaller problem than a redirect
 * that 500s because the analytics table is unavailable.
 *
 * At production volume this listener would be replaced by a Kafka producer plus a separate
 * consumer, so that events survive a pod restart. That is a deliberate scope cut, not an
 * oversight; see docs/ENGINEERING-SUMMARY.md.
 */
@Component
public class ClickEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClickEventListener.class);

    private final ClickEventRepository repository;

    public ClickEventListener(ClickEventRepository repository) {
        this.repository = repository;
    }

    @Async("analyticsExecutor")
    @EventListener
    public void on(ClickRecorded event) {
        try {
            repository.save(new ClickEvent(
                    UUID.randomUUID(),
                    event.shortCode(),
                    event.occurredAt(),
                    event.referrerHost(),
                    event.userAgentFamily()));
        } catch (Exception e) {
            log.warn("Dropping click event for {}: {}", event.shortCode(), e.toString());
        }
    }
}
