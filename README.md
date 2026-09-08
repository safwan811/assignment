# URL Shortener — AI-Assisted Engineering Assignment

A URL shortener with creation, redirection, analytics, expiry, rate limiting and graceful
degradation. Java 17, Spring Boot 3.2, PostgreSQL, Redis.

The service is the vehicle. The point of the submission is the process around it: how the
requirement was interpreted, how work was decomposed, where AI was used and where its output was
rejected, and how each claim is validated. See [`docs/`](docs/) for that, starting with
[`docs/ENGINEERING-SUMMARY.md`](docs/ENGINEERING-SUMMARY.md).

---

## Quick start

### Option A — Docker Compose (nothing but Docker required)

```bash
docker compose up --build
```

Wait until the `app` container reports healthy (roughly 30–60 seconds on a cold build; the first
run downloads Maven dependencies). Then:

```bash
curl -s http://localhost:8080/actuator/health
```

### Option B — run locally against containerised dependencies

```bash
docker compose up -d postgres redis
mvn spring-boot:run
```

Requires JDK 17 and Maven 3.8+. Flyway creates the schema on startup; no manual SQL needed.

### Shut down

```bash
docker compose down          # keep data
docker compose down -v       # also drop the Postgres volume
```

---

## Walking through the API

Create a link:

```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/long/path?id=42"}' | jq
```

```json
{
  "shortCode": "aK3mZ9q",
  "shortUrl": "http://localhost:8080/aK3mZ9q",
  "originalUrl": "https://example.com/some/long/path?id=42",
  "status": "ACTIVE",
  "createdAt": "2026-09-03T10:15:00Z",
  "expiresAt": null
}
```

Follow the redirect (`-i` to see the 302 and the `Location` header):

```bash
curl -i http://localhost:8080/aK3mZ9q
```

Same URL again → same code (creation is idempotent per caller):

```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/long/path?id=42"}' | jq -r .shortCode
```

Force a second, distinct code for the same URL:

```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/long/path?id=42","forceNew":true}' | jq -r .shortCode
```

Custom alias and expiry:

```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/promo","customAlias":"summer","expiresAt":"2027-01-01T00:00:00Z"}' | jq
```

Analytics (writes are asynchronous, so allow a moment after clicking):

```bash
curl -i http://localhost:8080/summer -H 'Referer: https://news.example.com/story'
curl -i http://localhost:8080/summer -H 'User-Agent: Googlebot/2.1'
sleep 1
curl -s http://localhost:8080/api/v1/links/summer/stats | jq
```

Metadata and disable:

```bash
curl -s http://localhost:8080/api/v1/links/summer | jq
curl -i -X DELETE http://localhost:8080/api/v1/links/summer
curl -i http://localhost:8080/summer     # now 410 Gone
```

### Guardrails worth trying

```bash
# Non-http scheme -> 400
curl -s -X POST http://localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"javascript:alert(1)"}' | jq

# Cloud instance metadata -> 400
curl -s -X POST http://localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"http://169.254.169.254/latest/meta-data/"}' | jq

# Reserved alias -> 400
curl -s -X POST http://localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","customAlias":"actuator"}' | jq

# Rate limit -> 429 after 60 creates in a minute
for i in $(seq 1 65); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST http://localhost:8080/api/v1/links \
    -H 'Content-Type: application/json' -d "{\"url\":\"https://example.com/p$i\"}"
done; echo
```

---

## API reference

| Method | Path | Purpose | Success | Notable failures |
|--------|------|---------|---------|------------------|
| POST | `/api/v1/links` | Create a short link | 201 + `Location` | 400 invalid URL / alias / expiry, 409 alias taken, 429 rate limited |
| GET | `/{code}` | Redirect | 302 + `Location` | 404 unknown, 410 expired or disabled |
| GET | `/api/v1/links/{code}` | Link metadata | 200 | 404 |
| GET | `/api/v1/links/{code}/stats` | Click analytics | 200 | 404 |
| DELETE | `/api/v1/links/{code}` | Disable (soft delete) | 204 | 404 |
| GET | `/actuator/health` | Liveness / readiness | 200 | — |

**Create request fields**

| Field | Required | Notes |
|-------|----------|-------|
| `url` | yes | `http`/`https` only, max 2048 chars, must not resolve to a private or loopback address |
| `customAlias` | no | `[A-Za-z0-9_-]{3,64}`, cannot be a reserved word |
| `expiresAt` | no | Absolute UTC instant, must be in the future, max 5 years out |
| `forceNew` | no | `true` skips deduplication and mints a fresh code |

Caller identity comes from `X-Api-Key` when present, otherwise the remote address. It scopes both
rate limiting and link ownership, so two callers shortening the same URL get separate links and
separate analytics.

**Error shape** — every failure, including 429 and 500:

```json
{
  "code": "INVALID_REQUEST",
  "message": "Only http and https URLs are supported",
  "details": [],
  "correlationId": "0f0c5d1a-...",
  "timestamp": "2026-09-03T10:15:00Z"
}
```

`correlationId` is echoed in the `X-Correlation-Id` response header and appears on every log line
for that request.

---

## Testing

```bash
mvn test      # unit tests only, no Docker needed        (~5s)
mvn verify    # unit + integration, requires a Docker daemon
```

**Unit tests** (`*Test.java`, run by Surefire) cover URL normalisation, SSRF and length
validation, code generation, and the full create/resolve/disable logic of `LinkService` against a
hand-written in-memory store with the same uniqueness semantics as PostgreSQL.

**Integration tests** (`*IT.java`, run by Failsafe) start real PostgreSQL and real Redis with
Testcontainers, then drive the service over HTTP:

| Test | What it proves |
|------|----------------|
| `LinkLifecycleIT` | create → redirect → disable end to end, query strings preserved, SSRF and alias rules enforced through the real stack |
| `AnalyticsIT` | clicks are eventually recorded with referrer and agent attribution; redirects do not wait on analytics |
| `ConcurrentCreationIT` | 20 simultaneous creates of one URL converge on a single link; concurrent alias creates produce exactly one winner |
| `RateLimitIT` | creation throttles at the configured limit; redirects are never throttled |
| `CacheDegradationIT` | with Redis pointed at a dead port, redirects fall back to PostgreSQL and creation still succeeds |

If Docker is not available, `mvn verify` will fail at the integration stage. `mvn test` still
passes and is the fast feedback loop.

See [`docs/TESTING.md`](docs/TESTING.md) for the coverage rationale and what is deliberately not
tested.

---

## Configuration

Everything below is overridable by environment variable; defaults are in
`src/main/resources/application.yml`.

| Property | Default | Meaning |
|----------|---------|---------|
| `shortener.base-url` | `http://localhost:8080` | Prefix used to build `shortUrl` |
| `shortener.code-length` | `7` | Base62 characters per generated code |
| `shortener.max-collision-retries` | `5` | Generation attempts before returning 503 |
| `shortener.l1-ttl` | `30s` | Pod-local cache TTL; bounds how long a disabled link can still redirect on another instance |
| `shortener.redis-ttl` | `30m` | Shared cache TTL |
| `shortener.negative-cache-ttl` | `20s` | How long a "no such code" result is cached |
| `shortener.max-expiry` | `1825d` | Ceiling on `expiresAt` |
| `shortener.create-requests-per-minute` | `60` | Per-caller creation limit |
| `shortener.block-private-addresses` | `true` | Reject private, loopback and link-local destinations |
| `shortener.reserved-aliases` | see yml | Aliases that would shadow real routes |

---

## Documentation map

| Document | Contents |
|----------|----------|
| [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) | Interpretation of the brief, ambiguities and how each was resolved, acceptance criteria |
| [`docs/PLAN.md`](docs/PLAN.md) | Task decomposition with dependencies and quality gates |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Components, request flows, data model, scale-out path |
| [`docs/TESTING.md`](docs/TESTING.md) | Test strategy, what each layer covers, gaps |
| [`docs/RISKS.md`](docs/RISKS.md) | Risk register with mitigations and residual risk |
| [`docs/AI-USAGE.md`](docs/AI-USAGE.md) | How AI was used, prompting discipline, sign-off policy, secure usage rules |
| [`docs/ENGINEERING-SUMMARY.md`](docs/ENGINEERING-SUMMARY.md) | Plan, rationale, artifacts, trade-offs, assumptions, limitations |
| [`docs/adr/`](docs/adr/) | Architecture decision records for the contested choices |
| [`docs/scenarios/`](docs/scenarios/) | Greenfield, brownfield and ambiguous scenarios, each with decomposition → execution → validation |
| [`docs/ai-log/`](docs/ai-log/) | Per-task record of what AI produced, what was edited, what was rejected and why |

---

## Known limitations

Stated plainly rather than buried; each is expanded in `docs/ENGINEERING-SUMMARY.md`.

- Click events are written by an in-process async listener, so events queued at the moment a pod
  is killed are lost. Kafka is the production answer; it was cut to keep scope honest.
- No authentication. Caller identity is an unverified header or the remote IP, which is adequate
  for a prototype and not for production multi-tenancy.
- Redis failure is handled by try/catch fallback, not a circuit breaker, so a hard-down Redis adds
  its connect timeout to requests until it recovers.
- Expired links are rejected at read time but never purged; a cleanup job is needed at volume.
- Analytics queries scan `click_events` directly. Past a few million rows per link these need
  pre-aggregated rollups.
- Load testing was not performed; no latency numbers are claimed anywhere in these documents.
