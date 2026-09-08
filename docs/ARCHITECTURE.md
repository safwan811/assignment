# Architecture

## Shape of the system

A modular monolith. One deployable, with package boundaries drawn where a service split would go
if this needed to scale past a single team.

```
                       ┌─────────────────────────────────────────────┐
   POST /api/v1/links  │  web/                                       │
   GET  /{code}    ───▶│    CorrelationIdFilter                      │
   GET  /…/stats       │    SecurityHeadersFilter                    │
   DELETE /…           │    RateLimitFilter  (POST /api/v1/links only)│
                       │    LinkController · RedirectController      │
                       │    GlobalExceptionHandler                   │
                       └───────────────┬─────────────────────────────┘
                                       │
                       ┌───────────────▼─────────────────────────────┐
                       │  service/                                   │
                       │    UrlNormalizer → UrlValidator             │
                       │    ShortCodeGenerator                       │
                       │    LinkService     (create · resolve · disable)
                       │    AnalyticsService (read-side aggregates)  │
                       └───┬───────────────────────┬─────────────────┘
                           │                       │
              ┌────────────▼──────────┐   ┌────────▼──────────────────┐
              │ cache/                │   │ analytics/                │
              │   L1 Caffeine (pod)   │   │   ClickRecorded event     │
              │   L2 LinkCache(Redis) │   │   ClickEventListener @Async│
              └────────────┬──────────┘   └────────┬──────────────────┘
                           │                       │
                       ┌───▼───────────────────────▼──────┐
                       │ repository/  →  PostgreSQL       │
                       │   links · click_events           │
                       │   (Flyway-managed schema)        │
                       └──────────────────────────────────┘
```

**Why a monolith.** Splitting redirect and analytics into separate services would be the right
call at scale, and the package boundaries here are drawn so that split is mechanical. Doing it now
would have bought distributed-systems complexity and paid for it with the day that went into
integration tests. That is the trade-off, made deliberately.

## Control flow

### Create — `POST /api/v1/links`

1. `RateLimitFilter` checks a per-caller counter in Redis. Fails open if Redis is down.
2. Bean Validation checks presence and size only.
3. `UrlNormalizer` canonicalises scheme, host, default port and path; drops the fragment;
   **preserves the query string** (ADR-002).
4. `UrlValidator` rejects non-http(s) schemes, over-long URLs, and destinations that are loopback,
   private, link-local or wildcard addresses.
5. `LinkService` computes a dedup key. Normal create: `<owner>|<sha256(normalisedUrl)>`.
   `forceNew` or a custom alias: a random key that can never collide.
6. If deduplicating, look up the dedup key. An existing **active** link is returned as is. An
   existing **disabled** link is not reused — reusing it would silently re-enable something an
   operator switched off — so a fresh key is minted instead.
7. Otherwise generate a code and issue `INSERT … ON CONFLICT DO NOTHING`. On zero rows inserted,
   work out which constraint lost: an alias conflict is 409; a dedup-key conflict means a
   concurrent request won the race and its link is returned; anything else is a code collision and
   the loop retries. After `max-collision-retries`, 503 rather than an unbounded loop.
8. Populate both cache tiers, return 201 with a `Location` header.

### Redirect — `GET /{code}`

1. `Cache.get(code, loader)` on Caffeine. Per-key computation means a burst of misses on a hot
   code collapses into one loader call per pod rather than one per request.
2. Loader consults Redis. On a miss it reads PostgreSQL and populates Redis, negatively caching a
   genuine miss with a short TTL.
3. Status and expiry are evaluated **after** the cache read, against the injected `Clock`, so an
   expired link cannot be served from a still-warm cache entry.
4. A `ClickRecorded` event is published and the response returns immediately. Publishing is
   fire-and-forget; the redirect never waits for the analytics write.
5. 302, not 301 — see ADR-004.

The route pattern is `/{code:[A-Za-z0-9_-]{3,64}}`, which cannot match `/api/…` or `/actuator/…`.
The reserved-alias list is the second layer of that same defence.

### Analytics write

`ClickEventListener` runs on a dedicated bounded executor with a discard policy. Under extreme
load analytics degrades before redirects do, which is the intended ordering: an under-counted
dashboard is a smaller problem than a redirect that 500s.

### Analytics read — `GET /api/v1/links/{code}/stats`

Existence is checked first so an unknown code returns 404 rather than a plausible page of zeroes.
Aggregation happens in PostgreSQL (`date_trunc`, `GROUP BY`, `LIMIT`), so the response size is
bounded by days and top-N, not by click volume.

## Data model

**`links`**

| Column | Notes |
|--------|-------|
| `id` | UUID primary key |
| `short_code` | **unique** — the redirect lookup and the uniqueness invariant |
| `original_url` | normalised destination, ≤ 2048 chars |
| `url_fingerprint` | SHA-256 of the normalised URL; a 64-char index key instead of a 2 KB one |
| `dedup_key` | **unique** — `<owner>\|<fingerprint>`, or a random value when dedup is opted out |
| `owner` | caller identity; scopes dedup and analytics |
| `status` | `ACTIVE` / `DISABLED`, with a CHECK constraint |
| `created_at`, `expires_at` | `TIMESTAMPTZ` |

`dedup_key` exists because a plain unique index on `(url_fingerprint, owner)` makes `forceNew`
impossible — a contradiction a unit test caught after the API had already been designed
(`docs/ai-log/T05-create-endpoint.md`). Indirecting through a key keeps uniqueness enforced in the
database on every path, instead of having application code decide when the constraint applies.

**`click_events`** — append-only, one row per served redirect, indexed on
`(short_code, occurred_at DESC)`. No foreign key to `links`: the table is written asynchronously
and an FK check per insert would couple analytics throughput to the links table. No IP column
(ADR-003).

## Correctness under concurrency

The rule that shapes the whole service: **instances do not share memory, so uniqueness lives in
the database.** No `synchronized`, no application-level lock, no check-then-insert.

`ON CONFLICT DO NOTHING` rather than catching `DataIntegrityViolationException`: in PostgreSQL a
constraint violation poisons the surrounding transaction, so a catch-and-retry loop cannot
continue in the same transaction. `ConcurrentCreationIT` is the test that would fail if someone
"simplified" this back to a check-then-insert.

## Failure behaviour

| Failure | Behaviour | Verified by |
|---------|-----------|-------------|
| Redis unreachable | Reads fall through to PostgreSQL; writes to cache are logged and skipped; rate limiter fails open | `CacheDegradationIT` |
| PostgreSQL unreachable | Requests fail fast with 503 via the Hikari connection timeout (2s). No partial writes | Not automated — stated in `docs/TESTING.md` |
| Analytics executor saturated | Events discarded; redirects unaffected | Bounded queue + `DiscardPolicy` in `AsyncConfig` |
| Analytics write throws | Logged and swallowed; redirect already returned | `AnalyticsIT` |
| Code collision | Bounded retry, then 503 | `LinkServiceTest.failsClosedAfterRepeatedCollisions` |
| Link disabled while cached | L1 and L2 evicted immediately on the acting pod; other pods lag by at most `l1-ttl` (30s) | `LinkLifecycleIT`, and the window is in `docs/RISKS.md` |

## Observability

Correlation id on every request (`X-Correlation-Id`, echoed in the response and in the MDC on
every log line, bounded to 64 chars because the header is attacker-controlled). Actuator exposes
health, info and metrics only. Error responses never carry internal exception text — that goes to
the log.

## Scale-out path

Roughly in the order the pain would arrive:

1. **Redirect read volume** → the service is stateless; add instances behind a load balancer. The
   Caffeine tier is per-pod, so cache hit rate degrades slightly as pods multiply; Redis absorbs it.
2. **Analytics write volume** → replace the in-process listener with a Kafka producer and a
   separate consumer. `ClickRecorded` is already a standalone record with no JPA coupling, so this
   is a contained change.
3. **Analytics read volume** → pre-aggregated daily rollups, so `stats` stops scanning raw events.
4. **Redirect latency at the edge** → a CDN in front, accepting that edge caching and per-click
   analytics are in tension (ADR-004 covers the same tension for 301).
5. **Write volume on `links`** → partition or shard by `short_code`. Nothing in the current design
   depends on a global sequence, precisely because codes are random rather than sequential.
