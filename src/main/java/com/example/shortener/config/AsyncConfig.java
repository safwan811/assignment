package com.example.shortener.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated executor for click analytics.
 *
 * Bounded queue with a discard policy is a deliberate trade-off: under extreme load we
 * would rather lose analytics events than let an unbounded queue exhaust the heap and
 * take down redirects. Analytics is best-effort; redirects are not.
 */
@Slf4j
@Configuration
public class AsyncConfig {

    @Bean(name = "analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("analytics-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        log.info("Analytics executor initialised: core=2 max=8 queue=10000 rejection=discard");
        return executor;
    }
}
