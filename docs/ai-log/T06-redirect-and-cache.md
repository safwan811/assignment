# T06 — Redirect path and two-tier cache

## Prompt contract

**Intent.** `GET /{code}` resolving through Caffeine (pod-local) then Redis then PostgreSQL.

**Constraints.**
- Redis is a cache, never a source of truth. A Redis outage must degrade latency, not availability.
- The redirect route must not shadow `/api/**` or `/actuator/**`.
- Expiry and disabled status must be evaluated after the cache read, not baked into what is cached.

**Acceptance criteria.** Redirects work with Redis unreachable. A disabled link stops redirecting.
An expired link returns 410, not 404.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| Cache-aside implementation | Edited | Generated version cached the *decision* (resolved destination) rather than the link state. That meant an expired link stayed redirectable until its cache entry aged out. Changed to cache state and evaluate expiry per request against the injected `Clock`. |
| `@Transactional` on the redirect path | **Rejected** | Generated. A cache hit never touches the database, so this would hold a pool connection per redirect for nothing. |
| `Cache.get(key, loader)` | Accepted | Caffeine computes per key atomically within a pod, so a burst on a hot code collapses to one database read instead of one per request. This was the assistant's suggestion and it was the right one. |
| Redis failure handling | Edited | Generated code let `RedisConnectionFailureException` propagate, which would have turned a cache outage into a total outage. Every `LinkCache` method now catches, logs and reports a miss. |
| Negative caching | Edited | Generated with the same 30-minute TTL as positive entries. Cut to 20 seconds: a long negative TTL means a freshly created code is shadowed by an earlier miss. |
| Route pattern | Edited | Generated `@GetMapping("/{code}")` matched `/api` and `/actuator`. Constrained to `[A-Za-z0-9_-]{3,64}`, with the reserved-alias list as the second layer. |
| 301 vs 302 | Edited | Generated 301. Changed to 302 and written up in ADR-004: a cached 301 breaks both click counting and revocation, and revocation is the one that matters. |

## Validation

`CacheDegradationIT` runs a whole context against a dead Redis port: redirects fall back to
PostgreSQL, creation still succeeds, and a disabled link is still 410. Pointing at a closed port
rather than stopping the shared container, because stopping it changes its mapped port and breaks
every other test in the run.

## Reviewer sign-off

**High-impact: redirect logic + cache invalidation.** Reviewer: ______________  Date: ____________
