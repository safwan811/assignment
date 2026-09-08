# Testing approach

## Two tiers, split on purpose

```bash
mvn test      # unit only, no Docker, ~5 seconds
mvn verify    # unit + integration, requires a Docker daemon
```

The Surefire/Failsafe split exists so the fast loop stays fast. A suite that needs Docker to run at
all gets run less often, and a test suite that is run less often stops catching things.

**Current state:** 63 unit tests pass. The integration tests compile and are written against real
containers; run `mvn verify` on a machine with Docker to execute them.

## What each tier is for

**Unit tests** cover logic that is pure or nearly pure, where a fast, exhaustive check is possible:

| Class | Tests | Covers |
|-------|-------|--------|
| `UrlNormalizerTest` | 17 | Scheme/host/port canonicalisation, query preservation, fragment removal, rejected schemes, malformed input |
| `UrlValidatorTest` | 13 | Loopback, private, link-local, wildcard and IPv6 targets; cloud metadata address; length cap; configurability |
| `ShortCodeGeneratorTest` | 3 | Alphabet and length, non-enumerability across 10k draws, configurable length |
| `LinkServiceTest` | 16 | Create, dedup per owner, cross-owner isolation, `forceNew`, alias rules, expiry bounds, collision retry and exhaustion, resolve, expire, disable |
| `RedirectControllerHeaderParsingTest` | 12 | Referrer host extraction, user-agent bucketing, Edge-vs-Chrome ordering |
| `GlobalExceptionHandlerTest` | 2 | Status mapping, and that internal exception text never reaches the client |

`LinkServiceTest` runs against `InMemoryLinkStore`, a hand-written fake with the same uniqueness
semantics as PostgreSQL — `insertIfAbsent` returns 0 when either the short code or the dedup key is
taken. Hand-written rather than a Mockito mock so the retry and race-resolution branches are
exercised against realistic behaviour instead of against whatever a mock was told to return.
`ScriptedCodeGenerator` makes collision handling deterministic rather than waiting for
`SecureRandom` to repeat itself.

Time is injected as a `Clock` everywhere, so expiry is tested by advancing a `MutableClock` rather
than by sleeping.

**Integration tests** cover everything that depends on the runtime rather than the code:

| Class | Proves |
|-------|--------|
| `LinkLifecycleIT` | create → redirect → disable over real HTTP; query strings survive; SSRF, alias and reserved-word rules hold through the full stack; correlation id header present |
| `AnalyticsIT` | clicks are eventually counted with referrer and agent attribution; a redirect succeeds independently of analytics; unknown code → 404 |
| `ConcurrentCreationIT` | 20 simultaneous creates of one URL → exactly one link; 10 concurrent alias creates → exactly one winner, the rest 409 |
| `RateLimitIT` | creation throttles at the configured limit with a machine-readable code; redirects are never throttled |
| `CacheDegradationIT` | with Redis pointed at a dead port: redirects fall back to PostgreSQL, creation still succeeds, disabled links are still 410 |

## Decisions worth defending

**Real PostgreSQL and real Redis, not H2 and not an embedded fake.** Everything this service leans
on — `ON CONFLICT DO NOTHING`, `TIMESTAMPTZ` semantics, `date_trunc`, btree index key limits, Redis
TTL — is exactly what an in-memory substitute gets subtly wrong. An H2 suite here would be
reassuring and useless.

**`ConcurrentCreationIT` is the load-bearing test.** It is the one that fails if someone
"simplifies" the conditional insert back into a check-then-insert. That refactor passes every
single-threaded test in the suite.

**`AnalyticsIT` polls instead of asserting immediately.** Asynchronous writing is the contract, not
a limitation. A synchronous assertion would pass today and would keep passing if the write
accidentally moved back onto the request thread — hiding the exact regression that matters.

**`CacheDegradationIT` points at a dead port rather than stopping the container.** Stopping the
shared Redis container changes its mapped port on restart and breaks every other test in the run.
A separate context aimed at the discard port (127.0.0.1:9) is deterministic and fails fast.

**Each integration class registers its own properties.** `@DynamicPropertySource` on a base class
outranks `@TestPropertySource` on a subclass, so an inherited registration would have silently
overridden `RateLimitIT`'s low limit and made it pass for the wrong reason. Registration lives in
each concrete class instead.

## What is deliberately not tested

Stated rather than left for a reviewer to find:

- **PostgreSQL outage.** Behaviour is a fast 503 via the 2-second Hikari timeout. Testing it
  properly needs Toxiproxy or a container pause; the fallback path has no branching logic, so the
  value did not justify the setup cost within the time budget.
- **Load and latency.** No load test was run, so no latency numbers are claimed anywhere in these
  documents. The two-tier cache is justified by reasoning, not by measurement, and that distinction
  is deliberate.
- **L1 staleness window across pods.** A disabled link can still redirect on another instance for
  up to `l1-ttl` (30s). Reproducing this needs a multi-instance test harness. The window is bounded
  by configuration and recorded in `RISKS.md`.
- **Flyway rollback.** Migrations are forward-only here. A production service needs a tested
  rollback path.
- **Analytics event loss on pod kill.** The known consequence of the in-process executor. Testing it
  would mean asserting on a limitation rather than fixing it; the fix is Kafka.

## Coverage

No coverage threshold is enforced. A percentage gate encourages tests written to raise a number,
and the tests that matter here — concurrency, degradation, cache invalidation — are the ones a
coverage tool values least. The gate applied instead is per-change: new behaviour needs a test that
fails without the change.
