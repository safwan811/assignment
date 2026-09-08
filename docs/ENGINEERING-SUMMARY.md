# Engineering summary

The document to read if you only read one. Plan, decisions and their rationale, what exists, what
was traded away, and what is genuinely missing.

---

## 1. What was built

A URL shortener: create with per-caller deduplication, custom aliases and optional expiry; 302
redirect through a two-tier cache; asynchronous click analytics; rate limiting; soft delete. Java
17, Spring Boot 3.2, PostgreSQL, Redis, all containerised.

**Verified:** 63 unit tests pass against JDK 17. **Not verified by me:** the integration suite
compiles but requires a Docker daemon — run `mvn verify` before relying on it. No load test was
run, so no latency figure is claimed anywhere in these documents.

## 2. Plan and rationale

Three days, three scenarios, ordered so each builds on a working base:

| Day | Scenario | Output |
|-----|----------|--------|
| 1 | Greenfield | Requirements, ADRs, core service, unit tests |
| 2 | Brownfield + ambiguous | Analytics, reliability and abuse controls, integration tests |
| 3 | Hardening | Documentation, risk register, review of every claim against its evidence |

The shaping rule throughout: **decide the contested things before generating code.** Query-string
handling, code generation strategy and redirect status were settled in ADRs first. Each of those
would have meant rewriting working code if decided afterwards, and each is a question an
interviewer will ask.

## 3. Artifacts

| Artifact | Location |
|----------|----------|
| Runnable service | `docker compose up --build` |
| Requirements and ambiguity resolution | `docs/REQUIREMENTS.md` |
| Task decomposition | `docs/PLAN.md` |
| Architecture, control flow, scale-out path | `docs/ARCHITECTURE.md` |
| Decision records | `docs/adr/ADR-001`…`004` |
| Three scenarios | `docs/scenarios/` |
| Test strategy and gaps | `docs/TESTING.md` |
| Risk register | `docs/RISKS.md` |
| AI approach, gates, secure usage | `docs/AI-USAGE.md` |
| Per-task AI disposition log | `docs/ai-log/` |
| Review checklist | `.github/pull_request_template.md` |

## 4. Key decisions

| Decision | Alternative rejected | Why |
|----------|---------------------|-----|
| Random Base62 codes | Base62-encoded DB sequence | A sequence makes every tenant's links enumerable (ADR-001) |
| Preserve the query string | Strip all query params | Query params usually select the destination; stripping them silently redirects users to the wrong page (ADR-002) |
| 302 | 301 | A cached 301 breaks click counting and, more importantly, makes revocation impossible (ADR-004) |
| Store coarse click attributes | IP + full user agent | Storing them turns a shortener into a tracking system with personal-data obligations (ADR-003) |
| Uniqueness in the database | Application-level locking | Instances do not share memory |
| `ON CONFLICT DO NOTHING` | Catch duplicate-key and retry | A constraint violation poisons the PostgreSQL transaction; the retry could not proceed |
| `dedup_key` column | Unique index on `(fingerprint, owner)` | The direct index makes `forceNew` impossible — see §7 |
| Modular monolith | Split redirect/analytics services | Distributed complexity would have cost the day that went into integration tests |
| In-process async analytics | Kafka | Same reason; the cost is stated in §8 |
| Rate limiter fails open | Fails closed | A closed limiter converts a cache outage into a write outage |
| Spring MVC | WebFlux | On Java 17 there are no virtual threads, so WebFlux is the real concurrency option — and it adds R2DBC, reactive Redis, harder debugging and materially worse AI-generated code quality, for throughput this prototype does not need |

## 5. Trade-offs made knowingly

**Scope over breadth.** Kafka, Spring Security and a circuit breaker were all cut. Each is the
right production answer in its area. They were cut so the time went into integration tests instead,
because a service with fewer moving parts and real tests is more defensible than one with more
moving parts and claims.

**Deduplication scoped per owner.** More rows than a global dedup, but two tenants shortening the
same URL must not observe each other's links or analytics.

**Analytics correctness under load.** The executor discards rather than queues without bound. An
under-counted dashboard beats redirects failing because the heap is exhausted.

**Cache staleness.** The pod-local tier gives stampede protection on hot codes and costs a
30-second window in which another instance can still serve a link that was just disabled. Bounded
by configuration and recorded as R7.

## 6. Validation

Every behavioural claim in these documents points at a test that exists. The load-bearing ones:

| Claim | Test |
|-------|------|
| Concurrent creates converge on one link | `ConcurrentCreationIT` |
| Redirects survive a Redis outage | `CacheDegradationIT` |
| Analytics never blocks a redirect | `AnalyticsIT` |
| Creation throttles; redirects do not | `RateLimitIT` |
| Expiry and disable both yield 410 | `LinkServiceTest`, `LinkLifecycleIT` |
| SSRF-shaped destinations rejected | `UrlValidatorTest`, `LinkLifecycleIT` |
| Internal errors never leak detail | `GlobalExceptionHandlerTest` |

`docs/TESTING.md` lists what is deliberately **not** tested — Postgres outage, load, the
cross-pod staleness window, migration rollback — rather than leaving a reviewer to find the gaps.

## 7. Where the process earned its keep

A unit test failed on first run: `forceNew` could never work. The schema had a unique index on
`(url_fingerprint, owner)`; `forceNew` skipped the application-level dedup check but still hit that
index, so the insert conflicted, the race-resolution branch found the existing row, and the call
silently returned the *original* code. The API documented a flag the schema made impossible.

Two things are worth saying about it:

1. The defect was in the **design**, not in generated code. It existed before a line was written.
   No amount of careful review of the generated implementation would have found it, because the
   implementation correctly implemented a contradictory design.
2. It was found because the acceptance criteria were written before the code. A test asserting
   "`forceNew` produces a different code" existed to fail.

The fix introduces a `dedup_key` column — `<owner>|<fingerprint>` normally, a random value when
dedup is opted out — so uniqueness stays enforced in the database on every path, rather than having
application code decide when the constraint applies. A follow-on decision surfaced during the fix:
if the dedup key resolves to a *disabled* link, mint a fresh one, because reusing it would silently
re-enable something an operator had switched off.

This is the strongest argument in the submission for the working method: constraints stated before
generation, acceptance criteria written before code, and tests that can fail.

## 8. Assumptions

- Single region, single instance acceptable for a prototype; the scale-out path is in
  `ARCHITECTURE.md`.
- Reviewer runs it locally with Docker; no cloud account needed.
- No authentication model. Caller identity is an unverified header or the remote IP — adequate for
  a prototype, not for production multi-tenancy.
- Java 17 is the target runtime, so Java 21 features (virtual threads in particular) are off the
  table.
- Deployment sits behind a proxy that would supply a trusted client IP; `ClientIdentity` needs a
  one-line change at that point.

## 9. Limitations

Ordered by how much they would matter in production:

1. **No destination reputation checking.** Nothing stops a phishing page being shortened. The
   largest gap; Safe Browsing plus a takedown workflow is the answer (R1).
2. **No authentication**, so rate limiting is defeated by rotating IPs (R3).
3. **Click events lost on pod termination** — the price of the in-process executor. Kafka fixes it
   (R13).
4. **No circuit breaker on Redis**, so a hard-down Redis adds its connect timeout to every request
   until recovery (R10).
5. **30-second cross-pod staleness window** after disabling a link. Redis pub/sub invalidation is
   the fix (R7).
6. **Analytics queries scan raw events.** Past a few million rows per link they need pre-aggregated
   rollups (R14).
7. **Expired rows never purged** (R15).
8. **Forward-only migrations**; no tested rollback path (R16).
9. **No load testing.** The two-tier cache is justified by reasoning, not measurement, and that
   distinction is deliberate.
10. **Only IP literals checked for SSRF.** A hostname resolving to a private address passes. The
    durable control is that the service never fetches the destination; any future feature that does
    must add egress filtering first (R2).

## 10. If there were another two days

In order: destination reputation checking at creation; API-key authentication so ownership and rate
limiting mean something; Kafka for analytics with a separate consumer; a circuit breaker on Redis;
pub/sub cache invalidation to close the staleness window; a load test to replace reasoning with
numbers on the cache tiers.
