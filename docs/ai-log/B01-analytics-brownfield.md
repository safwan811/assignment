# B01 — Analytics (brownfield change to a working service)

## Prompt contract

**Intent.** Add click analytics to the existing service without changing redirect behaviour.

**Constraints.**
- Redirect latency must not depend on the analytics write.
- No personal data: no raw IP, no full user agent, no referrer path.
- Aggregate in the database, not in the JVM.

**Acceptance criteria.** Clicks are eventually counted with referrer and agent attribution.
A redirect succeeds when analytics is failing. Stats for an unknown code are 404.

## Impact analysis (done before any code)

| Component | Change |
|-----------|--------|
| `RedirectController` | Publishes `ClickRecorded`. Adds header extraction. |
| `AsyncConfig` | New — dedicated bounded executor. |
| `analytics/` | New package: event record + async listener. |
| `click_events` | New table, migration V2. |
| `AnalyticsService`, `StatsResponse` | New read side. |
| `LinkController` | One new route. |
| `LinkService` | **Unchanged** — this was the goal. |

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| Synchronous counter increment on `links` | **Rejected** | Generated first. It puts a write — and row contention on the hottest row — directly in the redirect path. |
| `ApplicationEventPublisher` + `@Async` | Accepted | Keeps the write off the request thread with no new infrastructure. |
| Unbounded executor queue | Edited | Generated default. Bounded to 10,000 with a discard policy: under extreme load, losing analytics beats exhausting the heap and taking down redirects. |
| IP address column | **Rejected** | Generated `ip_address VARCHAR(45)` and a `country` column. Storing IP turns a shortener into a tracking system and brings personal-data obligations this service has no machinery for. ADR-003. |
| Full user agent column | **Rejected** | Same reasoning. Replaced with a coarse family bucket computed at capture time, so the raw value never enters a domain object. |
| Stats aggregation in Java | **Rejected** | Generated code loaded all click rows and grouped with streams. Response size would scale with click volume. Moved to SQL `date_trunc` / `GROUP BY` / `LIMIT`. |
| Foreign key `click_events.short_code → links` | **Rejected** | An FK check per insert couples analytics write throughput to the links table, for a table that is append-only and written asynchronously. |
| Existence check before stats | Edited | Generated version returned zeroes for an unknown code. Returns 404 now: a plausible-looking empty response is worse than an error. |

## Validation

`AnalyticsIT` polls with Awaitility rather than asserting immediately — that is the actual
contract, and a synchronous assertion would hide a regression where the write moved back onto the
request thread.

## Reviewer sign-off

**High-impact: schema change.** Reviewer: ______________  Date: ____________
