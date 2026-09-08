# Scenario 2 — Brownfield: add analytics to a working service

The service from Scenario 1 works and is under test. Add per-click analytics without changing
redirect behaviour.

## Codebase reasoning — impact analysis before any code

| Component | Impact | Detail |
|-----------|--------|--------|
| `RedirectController` | **Changed** | Publishes an event; extracts referrer host and agent family |
| `AsyncConfig` | New | Bounded executor for analytics writes |
| `analytics/` | New package | `ClickRecorded` record, `ClickEventListener` |
| `click_events` | New table | Migration V2 |
| `AnalyticsService`, `StatsResponse` | New | Read side |
| `LinkController` | **Changed** | One new route |
| `LinkService` | **Unchanged** | The explicit goal |
| `LinkCache`, `UrlNormalizer`, `UrlValidator` | **Unchanged** | |

Data flow added: `GET /{code}` → resolve (unchanged) → publish `ClickRecorded` → return 302.
Separately, on the analytics executor: consume → insert into `click_events`.

The redirect path gains one method call and no I/O. That was the constraint the design had to
satisfy, and stating it up front is what ruled out the synchronous counter.

## Decomposition

| # | Task | Depends on | Gate |
|---|------|-----------|------|
| 1 | Impact analysis (this section) | — | Reviewed before code |
| 2 | `click_events` migration + entity | 1 | Migration applies against real Postgres |
| 3 | Async plumbing: executor, event, listener | 2 | Redirect latency unchanged |
| 4 | Publish from `RedirectController` | 3 | Existing redirect tests still pass |
| 5 | `GET /…/stats` with SQL aggregation | 2 | Response size bounded by days and top-N |
| 6 | Regression run | 4, 5 | Whole suite green |

## Execution

Decisions:

- **Async, not a synchronous counter.** A counter on `links` puts a write, and row contention on
  the hottest row, directly in the redirect path.
- **Bounded queue with a discard policy.** Under extreme load, lose analytics rather than exhaust
  the heap. Analytics is best-effort; redirects are not.
- **No IP, no full user agent, no referrer path** (ADR-003). Minimisation happens at capture in
  `RedirectController`, so the raw values never reach a domain object and no later refactor can
  accidentally start persisting them.
- **Aggregate in SQL.** Generated code loaded every click row and grouped with streams; response
  size would have scaled with click volume.
- **No foreign key** from `click_events` to `links` — an FK check per insert couples analytics
  throughput to the links table.
- **404 for stats on an unknown code**, not a page of zeroes.

Rejections recorded in `ai-log/B01-analytics-brownfield.md`.

## Validation

| Claim | Evidence |
|-------|----------|
| Clicks are counted with attribution | `AnalyticsIT.recordsClicks` — 3 visits, referrer and agent split asserted |
| Redirects do not depend on analytics | `AnalyticsIT.redirectDoesNotDependOnAnalytics` |
| Unknown code → 404, not zeroes | `AnalyticsIT.statsForUnknownCodeAreNotFound` |
| Bots are distinguishable | `RedirectControllerHeaderParsingTest` |
| Existing behaviour unregressed | Full suite green after the change |

`AnalyticsIT` polls with Awaitility rather than asserting immediately — that is the actual
contract. A synchronous assertion would hide a regression where the write moved back onto the
request thread.

## Reflection

The valuable artifact here is the impact table, not the code. It is what made "`LinkService`
unchanged" a testable goal rather than a hope, and it is what made the synchronous-counter
suggestion obviously wrong before it was written.
