# Risk register

Ordered by what would hurt most in production. "Residual" is what remains after the mitigation
listed, stated honestly rather than optimistically.

## Security and abuse

### R1 — Shortener used to disguise a phishing or malware destination
**Impact:** high — reputational, and the domain gets blocklisted, which breaks every link.
**Mitigation:** scheme allowlist (`http`/`https` only), 2048-char cap, `DELETE` disables a link
immediately and permanently (codes are never reused).
**Residual:** high. There is no destination reputation check and no abuse reporting path. A
production service needs Google Safe Browsing or equivalent at creation time, plus a takedown
workflow. This is the single largest gap in the service.

### R2 — SSRF via a destination pointing at internal infrastructure
**Impact:** high if the service ever fetches the target (link previews, screenshots).
**Mitigation:** loopback, private, link-local, wildcard and multicast literals rejected;
`169.254.169.254` covered by the link-local rule and tested explicitly. Critically, **the service
never fetches the destination** — it only stores and returns it.
**Residual:** medium. Only IP literals are checked. A hostname resolving to a private address
passes, and DNS rebinding defeats resolution-time checking anyway. The durable control is the
no-fetch property, so any future feature that fetches a destination must add egress filtering
first. `UrlValidator`'s class comment says this so the constraint travels with the code.

### R3 — Bulk link creation exhausting storage or the code keyspace
**Impact:** medium.
**Mitigation:** per-caller fixed-window limit, 60/minute by default.
**Residual:** medium-high. Caller identity is an unverified `X-Api-Key` header or the remote
address, so rotating IPs bypasses it. Real mitigation requires authentication.

### R4 — Custom alias shadowing a real route
**Impact:** high — `/actuator` or `/api` being intercepted.
**Mitigation:** two independent layers. The redirect route pattern
`[A-Za-z0-9_-]{3,64}` cannot match a path containing `/`, and a reserved-alias list rejects the
known-dangerous words at creation.
**Residual:** low. A new route added under a new top-level path must be added to the reserved list;
the PR template's security checkbox is the reminder.

### R5 — Spoofed `X-Forwarded-For` bypassing the rate limit
**Impact:** medium.
**Mitigation:** the header is deliberately not trusted; `getRemoteAddr()` is used.
**Residual:** behind a load balancer every request appears to come from the balancer, so all
callers share one bucket. The deployment must supply a trusted client-IP header and
`ClientIdentity` must be changed to read it. Documented in that class.

## Correctness

### R6 — Concurrent creates producing duplicate or lost links
**Impact:** high — duplicate links fragment analytics; a lost create returns an error for a request
that should have succeeded.
**Mitigation:** uniqueness enforced by database constraints, `INSERT … ON CONFLICT DO NOTHING`,
re-read of the race winner. No JVM locks anywhere.
**Residual:** low. `ConcurrentCreationIT` covers both the URL and alias paths.

### R7 — A disabled link still redirecting from a warm cache
**Impact:** high when the link was disabled for abuse.
**Mitigation:** both cache tiers evicted synchronously on the acting instance.
**Residual:** **medium, and this is a real gap.** The Caffeine tier is per-pod, so other instances
keep serving the link for up to `shortener.l1-ttl` (30 seconds). Options considered: drop L1
entirely (loses stampede protection on hot codes), or publish invalidations over Redis pub/sub (the
correct fix, not implemented). 30 seconds is the deliberate bound.

### R8 — An expired link served from cache after expiry
**Impact:** medium.
**Mitigation:** the cache stores link *state*, not the resolved decision. Expiry is evaluated per
request against the injected `Clock`, after the cache read.
**Residual:** low. This was a defect in the first generated implementation; see
`ai-log/T06-redirect-and-cache.md`.

### R9 — Redirecting to the wrong destination through over-normalisation
**Impact:** high and near-invisible — the response is a valid 302 to a valid page, just the wrong
one.
**Mitigation:** query strings preserved byte for byte (ADR-002); only scheme, host, default port,
path traversal and fragment are touched.
**Residual:** low.

## Availability

### R10 — Redis outage
**Impact:** medium.
**Mitigation:** every `LinkCache` method catches and reports a miss; reads fall through to
PostgreSQL; the rate limiter fails open.
**Residual:** medium. There is no circuit breaker, so while Redis is hard-down every request pays
the connect timeout (500ms configured) before falling back. Resilience4j is the fix and was cut for
scope, not because it is unnecessary.

### R11 — PostgreSQL outage
**Impact:** total — it is the source of truth.
**Mitigation:** 2-second Hikari connection timeout so requests fail fast rather than piling up;
cached redirects continue to be served for the life of their cache entries.
**Residual:** high by design. Mitigating this properly means read replicas and connection-level
failover, which is infrastructure, not application code.

### R12 — Analytics load affecting redirect latency
**Impact:** medium.
**Mitigation:** bounded executor with a discard policy; publishing is fire-and-forget; write
failures are logged and swallowed.
**Residual:** low for latency. See R13 for the cost.

### R13 — Click events lost on pod termination
**Impact:** low — analytics accuracy only.
**Mitigation:** none. Queued events die with the process.
**Residual:** accepted. This is the price of choosing an in-process executor over Kafka, and it is
stated in the README rather than hidden.

## Data and operations

### R14 — `click_events` growing without bound
**Impact:** medium — query latency, then storage cost.
**Mitigation:** indexed on `(short_code, occurred_at DESC)`; aggregation happens in SQL with
`LIMIT`, so response size is bounded even as the table is not.
**Residual:** high at volume. Needs retention policy plus pre-aggregated daily rollups. Past a few
million rows per link the stats endpoint will degrade.

### R15 — Expired links never purged
**Impact:** low.
**Mitigation:** expiry is enforced at read time, so behaviour is correct regardless.
**Residual:** rows accumulate. A scheduled cleanup job is needed; it is deliberately absent because
a badly written one that deletes live links is worse than no job at all.

### R16 — Forward-only migrations
**Impact:** medium — a bad migration cannot be rolled back cleanly.
**Mitigation:** migrations are reviewed as high-impact changes with named sign-off.
**Residual:** medium. Production needs tested rollback scripts and expand/contract migration
discipline.

### R17 — Over-trusting AI-generated code
**Impact:** high, and the risk this whole exercise is about.
**Mitigation:** constraints stated before generation; per-task disposition log with rationale for
every rejection; sign-off required for schema, redirect logic, validation and cache behaviour;
every behavioural claim tied to a test that exists.
**Residual:** the honest answer is that review catches what the reviewer thinks to look for. The
one real design defect in this project (`forceNew` vs the unique index) was found by a failing
test, not by reading code. That is the argument for writing acceptance criteria before generating,
not after.
