# AI usage: approach, discipline and controls

## Principle

AI assists within tasks. The engineer owns the task boundary, the acceptance criteria, and the
correctness of what ships. Nothing was accepted because it compiled or because it looked right.

## How tasks were given to the assistant

Every non-trivial task was framed as a contract with four parts, before any code was requested:

1. **Intent** — the behaviour wanted, in one or two sentences.
2. **Constraints** — the non-negotiables. These are where the engineering judgment lives:
   "uniqueness must be enforced by the database, not application code", "Redis is a cache and never
   a source of truth", "Java 17, so no virtual threads".
3. **Acceptance criteria** — how the result would be checked, written before the code existed.
4. **Technical context** — the relevant existing code, the schema, the ADRs already decided.

The constraints do most of the work. Left to itself the assistant reached for check-then-insert
deduplication, an unbounded retry loop, a fail-closed rate limiter, and an IP address column. Each
of those is a reasonable default in isolation and wrong here, and each was caught by a constraint
that had been stated up front rather than by noticing it afterwards.

## Iterative refinement, in practice

The pattern that worked: ask for the smallest useful unit, read it, name the specific objection,
ask again with that objection as a new constraint. The pattern that did not work: asking for a
whole feature and reviewing the result. Large generated blocks are reviewed less carefully because
the volume is fatiguing, which is precisely when a plausible-looking race condition survives.

Three rounds was typical for anything touching concurrency or caching. `LinkCache` took four.

## Traceability

`docs/ai-log/` holds one file per task: the contract, a table of what was accepted, edited or
rejected, the reason in each case, and how the result was validated. The rejections are the
substantive part of that record — they are where an engineering decision was actually made.

Notable rejections, collected:

| Rejected | Why |
|----------|-----|
| Base62 sequence for short codes | Makes every tenant's links enumerable |
| Check-then-insert deduplication | Passes single-threaded tests, races in production |
| `catch (DataIntegrityViolationException)` retry | A constraint violation poisons the PostgreSQL transaction |
| Unbounded collision retry loop | Exhausted keyspace should fail visibly, not spin |
| Caching the resolved destination | Expired links stay redirectable until the entry ages out |
| `@Transactional` on the redirect path | Burns a pool connection on a cache hit |
| Letting Redis exceptions propagate | Turns a cache outage into a total outage |
| IP address and full user agent columns | Turns a shortener into a tracking system |
| Stats aggregation in Java | Response size scales with click volume |
| Fail-closed rate limiter | Converts a cache outage into a write outage |
| Trusting `X-Forwarded-For` | Spoofable; defeats the rate limit entirely |
| `@Pattern` regex for URL validation | Regex URL validation is reliably subtly wrong |
| Spring Security starter | A whole filter chain for four response headers |

## Where AI helped most, and least

**Most.** Boilerplate with a known shape: DTOs, exception hierarchy, Docker and Compose files, the
mechanical half of test classes. Also useful as an option generator — asking for four approaches to
short-code generation with trade-offs produced a better decision than starting from my own first
instinct, even though I rejected its recommendation.

**Least.** Anything where correctness depends on a property of the runtime rather than the code:
transaction semantics under constraint violation, cache invalidation windows across pods, what
happens to an async queue when a pod is killed. Generated code in those areas was consistently
plausible and consistently wrong in the same direction — optimistic about the happy path.

It was also no help at all with the one real design defect in this project. The `forceNew` /
unique-index contradiction (`docs/ai-log/T05-create-endpoint.md`) existed in the design before any
code was written. A failing test found it; no amount of reading generated code would have.

## Quality gates

| Gate | Mechanism | When |
|------|-----------|------|
| Compiles on the target JDK | Built against JDK 17 specifically, not JDK 21 with `--release 17` | Every change |
| Unit tests | `mvn test`, no Docker required | Every change |
| Integration tests | `mvn verify`, Testcontainers | Before merge |
| New behaviour is tested | A test that fails without the change | Every change |
| Secrets | No credentials, connection strings, customer data or internal hostnames in prompts or code | Every change |
| Dependency provenance | Every added dependency verified against Maven Central; none added on a model's assertion that it exists | Every change |
| High-impact sign-off | Named reviewer recorded in the AI log entry | See below |

## High-impact changes requiring explicit sign-off

- database migrations and schema changes
- redirect resolution logic
- input validation and other security controls
- cache read, write or invalidation behaviour

These are the areas where a defect is either silent (wrong redirect target), irreversible (a
migration), or a security exposure. Entries in `docs/ai-log/` carry a sign-off line, and the pull
request template in `.github/` repeats the checklist.

## Secure AI usage

- No credentials, tokens, connection strings, customer data or internal hostnames in any prompt.
- No proprietary code from other employers used as context.
- Generated SQL read in full before being run. Migrations are never applied straight from a
  response.
- Generated dependencies checked to exist before being added — a fabricated package name is a
  supply-chain risk, not a typo.
- Generated code involving cryptography or authentication treated as a starting point for reading
  documentation, never as an implementation. (`SecureRandom` here is the only such case.)
