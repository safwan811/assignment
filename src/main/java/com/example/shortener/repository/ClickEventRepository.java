package com.example.shortener.repository;

import com.example.shortener.domain.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ClickEventRepository extends JpaRepository<ClickEvent, UUID> {

    long countByShortCode(String shortCode);

    /**
     * Daily buckets. Aggregated in the database rather than by loading rows into the JVM,
     * so the response size is bounded by the number of days, not the number of clicks.
     */
    @Query(value = """
            SELECT to_char(date_trunc('day', occurred_at), 'YYYY-MM-DD') AS day, COUNT(*) AS clicks
            FROM click_events
            WHERE short_code = :shortCode AND occurred_at >= :since
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> countByDay(@Param("shortCode") String shortCode, @Param("since") Instant since);

    @Query(value = """
            SELECT referrer_host, COUNT(*) AS clicks
            FROM click_events
            WHERE short_code = :shortCode
            GROUP BY 1
            ORDER BY 2 DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> topReferrers(@Param("shortCode") String shortCode, @Param("limit") int limit);

    @Query(value = """
            SELECT user_agent_family, COUNT(*) AS clicks
            FROM click_events
            WHERE short_code = :shortCode
            GROUP BY 1
            ORDER BY 2 DESC
            """, nativeQuery = true)
    List<Object[]> countByUserAgentFamily(@Param("shortCode") String shortCode);
}
