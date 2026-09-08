# Execution Plan — Task Decomposition

Legend: **Dep** = depends on. **AI** = where AI assists. **Gate** = quality gate before task is "done".
Every task gets an entry in `docs/ai-log/<task-id>.md` (prompt → output → edited/rejected → rationale).

---

## Day 1 — Greenfield: core service

| ID | Task | Dep | AI use | Gate |
|----|------|-----|--------|------|
| T1 | Requirements doc + this plan | – | Structure, gap-finding | Self review |
| T2 | Project skeleton: Spring Boot, Maven, Docker Compose (Postgres, Redis), Actuator, profiles | T1 | Generate boilerplate, compose file | Boots clean, `/actuator/health` UP |
| T3 | Domain + schema: `links` table, Flyway migration, entity, repository | T2 | Draft schema; I review indexes/constraints | Migration applies; repository test |
| T4 | Short code generator: DB sequence → base62, reserved-word check | T3 | Generate encoder + property tests | Unit tests incl. edge cases (0, max long) |
| T5 | `POST /api/v1/links`: validation, idempotency, custom alias, TTL | T3, T4 | Generate controller/service/DTOs; I own validation rules | Unit + WebMvc tests; 400/409 paths |
| T6 | `GET /{code}` redirect with cache-aside (Redis) | T5 | Generate cache layer; I review TTL/invalidation | Integration test with Testcontainers |
| T7 | `GET`/`DELETE /api/v1/links/{code}` | T5 | Generate; review 404/410 semantics | Tests |
| T8 | Global error handling, request ID, structured logging | T2 | Generate advice/filters | Error format consistent |
| T9 | README: setup, curl examples | T6 | Draft; I verify each command | Fresh clone runs |

**End of day 1:** runnable shortener, tests green, AI log for T2–T9.

---

## Day 2 — Brownfield + Ambiguous

### Scenario B — Brownfield: add analytics to existing code
| ID | Task | Dep | AI use | Gate |
|----|------|-----|--------|------|
| B1 | Impact analysis: which modules/APIs/data flows change; write `docs/scenarios/brownfield.md` | Day 1 | Ask AI to map impact from codebase; I verify | Reviewed impact table |
| B2 | `click_events` table + migration; event model | B1 | Draft migration; I review indexes for time-range queries | Migration test |
| B3 | Async click recording: redirect publishes event → in-process async consumer writes DB | B2, T6 | Generate; I decide async boundary and failure behaviour | Redirect latency unaffected; event persisted (test) |
| B4 | `GET /api/v1/links/{code}/stats`: totals, daily buckets, top referrers | B2 | Generate queries; I check N+1 and index use | Tests with seeded data |
| B5 | Regression run + refactor pass (extract analytics module boundary) | B3, B4 | AI-suggested refactor; I accept/reject with rationale | All tests green; lint clean |
| B6 | Bug-fix sub-scenario: reproduce a real defect found during B3/B4 (or a seeded one), fix with AI-assisted debugging, add regression test | B5 | Debugging | Regression test passes |

### Scenario C — Ambiguous: "make it reliable under abuse and load"
| ID | Task | Dep | AI use | Gate |
|----|------|-----|--------|------|
| C1 | Clarify: list questions I'd ask a PM; document assumed answers in `docs/scenarios/ambiguous.md` | Day 1 | Generate candidate questions; I pick and answer | Doc reviewed |
| C2 | Rate limiting on create (Bucket4j or simple Redis token bucket) | T5 | Generate; I set limits and 429 contract | Test: limit enforced |
| C3 | Link expiry: TTL on create, 410 on redirect, scheduled cleanup | T6 | Generate scheduler; I review race conditions | Tests incl. boundary |
| C4 | Graceful degradation: Redis down → DB fallback; DB down → 503 fast | T6 | Generate resilience wrapper; I design fallback policy | Fault-injection integration test |
| C5 | Security pass: private-IP/loopback block, scheme allowlist, header hardening | T5 | AI review for open-redirect/SSRF; I validate findings | Tests for each blocked case |

**End of day 2:** analytics live, reliability features in, scenario docs written.

---

## Day 3 — Hardening + writeup

| ID | Task | Dep | AI use | Gate |
|----|------|-----|--------|------|
| H1 | Test coverage review; fill gaps on core paths | All | Generate missing tests; I review assertions | `mvn verify` green, coverage report |
| H2 | Static analysis + lint (Checkstyle, SpotBugs, dependency check) | All | Explain/fix findings | Zero high findings |
| H3 | Quick load check (k6 or `hey`) on redirect; record p95 | C4 | Generate script | Numbers in docs |
| H4 | `docs/ARCHITECTURE.md`: components, control flow, key decisions, scale-out notes | All | Draft diagrams (Mermaid) | Reviewed |
| H5 | `docs/ENGINEERING_SUMMARY.md`: plan/rationale, artifacts, risks, trade-offs, validation, assumptions, limitations | All | Draft from logs; I own conclusions | Reviewed |
| H6 | `docs/AI_USAGE.md`: prompting approach, traceability policy, sign-off policy, secure-usage rules (no secrets in prompts, review before commit) | All | – | Reviewed |
| H7 | Final fresh-clone run-through of README | H1–H6 | – | Works end-to-end |

---

## High-impact change policy (requires explicit sign-off note in AI log)
- Schema changes / migrations
- Redirect resolution logic
- Security validation rules
- Anything touching cache invalidation

## Suggested repo layout
```
url-shortener/
  src/main/java/.../{link,redirect,analytics,ratelimit,common}/
  src/test/...
  docker-compose.yml
  README.md
  docs/
    REQUIREMENTS.md
    PLAN.md
    ARCHITECTURE.md
    ENGINEERING_SUMMARY.md
    AI_USAGE.md
    scenarios/{greenfield,brownfield,ambiguous}.md
    ai-log/T2.md ... H7.md
```
