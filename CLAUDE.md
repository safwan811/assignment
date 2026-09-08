# Working agreement for AI-assisted changes in this repo

Load this as context before generating anything. It encodes decisions already made; re-litigating
them wastes a round trip.

## Environment

- **Java 17.** Not 21. No virtual threads, no Java 21 syntax.
- Spring Boot 3.2, Spring MVC (not WebFlux), Maven.
- PostgreSQL is the source of truth. Redis is a cache and never authoritative.
- Flyway owns the schema. Hibernate is `ddl-auto: validate` and never generates DDL.

## Non-negotiable constraints

1. **Uniqueness is enforced by database constraints, never by JVM locks.** Instances do not share
   memory. No `synchronized`, no application-level lock, no check-then-insert.
2. **Use `INSERT … ON CONFLICT DO NOTHING`**, not `catch (DataIntegrityViolationException)`. A
   constraint violation poisons the surrounding PostgreSQL transaction.
3. **A Redis failure must degrade latency, not availability.** Every cache call catches and reports
   a miss.
4. **The redirect path must not block on analytics** and must not open a transaction on a cache hit.
5. **No personal data in `click_events`**: no IP, no full user agent, no referrer path. Minimise at
   capture, not at write time.
6. **Time comes from the injected `Clock`**, never `Instant.now()`. Expiry must be testable without
   sleeping.
7. **Retry loops are bounded** with a defined failure response.
8. Errors go through `GlobalExceptionHandler`. Internal exception text never reaches the client.

## Already decided — see docs/adr, do not re-propose

- Random Base62 codes, not a DB sequence (ADR-001).
- Query string preserved; only the fragment is dropped (ADR-002).
- Coarse click attributes only (ADR-003).
- 302, not 301 (ADR-004).
- Modular monolith. In-process async analytics, with Kafka as the documented production path.
- Rate limiter fails open when Redis is unavailable.

## Process for every change

1. State intent, constraints and acceptance criteria **before** asking for code.
2. Ask for the smallest useful unit. Large generated blocks get reviewed less carefully, which is
   exactly when a plausible race condition survives.
3. New behaviour needs a test that fails without the change.
4. Record the outcome in `docs/ai-log/<task>.md`: accepted / edited / rejected, with the reason.
   Rejections are the substantive part of that record.
5. `mvn test` must pass without Docker. `mvn verify` before merge.

## High-impact changes — require a named reviewer sign-off in the AI log entry

- database migrations and schema changes
- redirect resolution logic
- input validation and other security controls
- cache read, write or invalidation behaviour

## Secure usage

No credentials, connection strings, API keys, customer data or internal hostnames in prompts. No
proprietary code from elsewhere as context. Verify a dependency exists on Maven Central before
adding it — a fabricated package name is a supply-chain risk, not a typo. Read generated SQL in
full before running it.
