# Requirements Interpretation — URL Shortener

> Purpose: normalize the interview brief into a clear engineering problem before writing code.
> Status: engineer-owned. AI assisted with structure and option generation; the decisions in the
> tables below are mine and are defended in `docs/adr/`.

## 1. Intent (as I read it)

Build a small but production-shaped URL shortener that demonstrates **engineer-led, AI-accelerated execution**. The service itself is the vehicle; the graded output is the process: requirement analysis, decomposition, traceable AI use, validation, and defensible decisions.

## 2. Functional scope

### In scope (must)
| ID | Requirement | Notes |
|----|-------------|-------|
| F1 | Create short link: `POST /api/v1/links` with a long URL → returns short code + short URL | Optional custom alias, optional TTL |
| F2 | Redirect: `GET /{code}` → 302/301 to original URL | 302 by default so analytics keep counting |
| F3 | Lookup metadata: `GET /api/v1/links/{code}` | Original URL, created at, expiry, click count |
| F4 | Delete/disable link: `DELETE /api/v1/links/{code}` | Soft delete; redirect returns 410 |
| F5 | Analytics: total clicks, clicks over time, top referrers, basic user-agent split | `GET /api/v1/links/{code}/stats` |
| F6 | Link expiry (TTL) | Expired → 410 Gone |

### Reliability / non-functional (must)
| ID | Requirement | Target for prototype |
|----|-------------|----------------------|
| N1 | Redirect latency | p95 < 50 ms with cache hit (local) |
| N2 | Idempotent creation | Same URL + same caller → same code (configurable) |
| N3 | Rate limiting on creation | Token bucket per client IP/API key |
| N4 | Input validation | Only `http`/`https`; block private/loopback ranges (SSRF/open-redirect hygiene); max length 2048 |
| N5 | Health + readiness endpoints | `/actuator/health` |
| N6 | Graceful degradation | Cache outage → fall back to DB; DB outage → fail fast with 503, no partial writes |
| N7 | Observability | Structured logs, request IDs, basic metrics |

### Out of scope (explicitly)
- Auth/user accounts beyond a static API key
- Multi-region / geo-distributed ID generation
- Real-time analytics dashboards (API only)
- Link preview / malware scanning (noted as future work)

## 3. Ambiguities identified and how I resolved them

| Ambiguity | Options considered | Decision | Why |
|-----------|--------------------|----------|-----|
| "Analytics" — how deep? | Counter only / per-click events / full clickstream | Per-click event rows plus aggregate queries | A counter cannot answer "clicks per day" or "top referrers" after the fact |
| "Reliability features" | Retries? Caching? Rate limits? HA? | Two-tier cache, rate limiting, expiry, graceful degradation, idempotent creation | These are the failure modes a reviewer will actually probe |
| 301 vs 302 redirect | Cacheable permanent vs trackable temporary | 302 | 301 is cached by browsers and intermediaries: we would stop seeing clicks and could not revoke a link someone has already visited |
| Short code generation | DB sequence + Base62 / hash / random Base62 | Random Base62, 7 chars, DB unique constraint + bounded retry | A sequence makes every other tenant's links enumerable (ADR-001) |
| URL normalisation | Strip query params / preserve them | Preserve the query string; drop only the fragment | Query params usually select the destination page; stripping them silently sends users somewhere else (ADR-002) |
| Custom aliases | Allow? Reserve words? | Allow, with a reserved list so an alias cannot shadow `/api` or `/actuator` | Cheap, and the shadowing failure is nasty |
| Duplicate long URLs | New code each time vs reuse | Reuse per owner; `forceNew=true` opts out via a unique dedup key | Idempotency is a reliability property; opting out must not weaken the DB constraint |
| Monolith vs microservices | Single service vs split redirect/analytics | Modular monolith with clean package boundaries | 2–3 days of work; boundaries kept extractable. Stated trade-off, not an accident |
| Persistence | In-memory / SQLite / PostgreSQL | PostgreSQL + Redis, both containerised | Production-shaped; the behaviours we rely on (ON CONFLICT, TIMESTAMPTZ) do not exist in H2 |
| Event transport for analytics | Kafka now / in-process async now | In-process async, Kafka documented as the production path | Kafka would have consumed the day that went into integration tests. Cost is stated in limitations |

## 4. Assumptions
- Reviewer will run locally via Docker Compose; no cloud account required.
- Single-region, single instance is acceptable for the prototype; design notes cover scale-out.
- Java 17 / Spring Boot 3.2 (matches the target environment; LTS).
- AI tools used are recorded per task in `docs/ai-log/`. All generated output is reviewed before commit.

## 5. Acceptance criteria (definition of done)
- `docker compose up --build` → service runs, every `curl` example in the README works end to end.
- Unit + integration tests pass (`mvn verify`), coverage on core paths.
- All three scenarios documented with decomposition → execution → validation.
- AI usage log complete: every task shows generated / edited / rejected with rationale.
- Architecture overview, testing approach, risks/trade-offs, limitations written.

## 6. Key risks (initial)
| Risk | Impact | Mitigation |
|------|--------|-----------|
| Open redirect / phishing abuse | Security | Scheme allowlist, private-IP block, rate limiting, disable endpoint |
| Cache/DB inconsistency | Wrong redirects | Cache-aside with TTL; invalidate on delete/update |
| Hot key on popular links | Latency | Redis cache; counters updated async |
| Analytics writes slowing redirects | Latency | Async event publish (in-process queue → Kafka noted as future) |
| Over-trusting AI-generated code | Correctness | Tests written/reviewed by me; sign-off checklist for high-impact changes |
