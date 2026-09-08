# Scenario 1 — Greenfield: build the core shortener

Nothing exists. Build create, redirect, lookup and disable.

## Decomposition

| # | Task | Depends on | Why it sits here |
|---|------|-----------|------------------|
| 1 | Requirements interpretation, ambiguities resolved | — | Deciding query-string handling *after* writing the normaliser means rewriting it |
| 2 | Skeleton: Boot 3.2 / Java 17, Postgres, Redis, Flyway, Actuator | 1 | |
| 3 | `links` schema + entity | 2 | The uniqueness model must exist before any code depends on it |
| 4 | Short code generation | 3 | Needs the unique constraint to lean on |
| 5 | `POST /api/v1/links` | 3, 4 | |
| 6 | `GET /{code}` + two-tier cache | 5 | Needs links to resolve |
| 7 | Lookup + disable | 5 | |
| 8 | Correlation id, security headers, uniform errors | 2 | Cross-cutting; retrofitting error shape is worse than starting with it |

Sequencing note: tasks 3 and 4 are where the design decisions live, and both were settled in ADRs
before code. Tasks 5–7 are execution.

## Execution

Decisions taken, each written up in an ADR:

- **Random Base62, not a sequence** (ADR-001) — a sequence makes every tenant's links enumerable.
- **Query string preserved** (ADR-002) — stripping it silently redirects users to the wrong page.
- **302, not 301** (ADR-004) — a cached 301 breaks both click counting and revocation.
- **Uniqueness in the database, never in the JVM** — instances do not share memory.

The concurrency shape follows from the last one: `INSERT … ON CONFLICT DO NOTHING`, then re-read to
find out which constraint lost. Not `catch (DataIntegrityViolationException)`, because in
PostgreSQL a constraint violation poisons the surrounding transaction and the retry cannot proceed.

AI rejections in this scenario: check-then-insert dedup, the exception-catch retry, an unbounded
retry loop, `@Transactional` on the redirect path, an unconstrained `/{code}` route that shadowed
`/api`. Full record in `ai-log/T02`–`T06`.

## Validation

| Claim | Evidence |
|-------|----------|
| Codes are not enumerable | `ShortCodeGeneratorTest` — 10k draws, ~10k distinct |
| Concurrent creates converge on one link | `ConcurrentCreationIT` — 20 threads, 1 row |
| Alias collisions are 409, not a silent alternate code | `ConcurrentCreationIT`, `LinkLifecycleIT` |
| Query strings survive shortening | `LinkLifecycleIT.preservesQueryString` |
| Redirect route cannot shadow the API | Route pattern + reserved list; `LinkLifecycleIT.reservedAliasRejected` |
| Errors never leak internal detail | `GlobalExceptionHandlerTest.hidesInternalDetail` |

## What this scenario surfaced

The `forceNew` defect (`ai-log/T05`). The API documented a flag that the schema made impossible.
Caught by a unit test on first run, fixed by introducing `dedup_key` so the uniqueness constraint
covers both the deduplicating and non-deduplicating paths.
