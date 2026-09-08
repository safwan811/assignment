# T07 — Testcontainers integration suite

## Prompt contract

**Intent.** Cover lifecycle, concurrency, and degradation behaviour through the real stack: real
PostgreSQL, real Redis, no in-memory substitutes.

**Constraints.**
- `mvn test` must pass without Docker (per `CLAUDE.md`). The `*IT` suite is Failsafe-bound, not
  Surefire-bound, so `mvn test` never touches it; only `mvn verify` does.
- `mvn verify` must pass with Docker running, before merge.

**Acceptance criteria.** `ConcurrentCreationIT`: 20 concurrent creates converge on one link, 10
concurrent alias creates produce one winner and 409s for the rest. `CacheDegradationIT`: redirects
and creation both survive a dead Redis. `LinkLifecycleIT`, `AnalyticsIT`, `RateLimitIT` exercise the
real HTTP + DB + cache path end to end.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| Shared containers via a static initialiser (`Containers.java`) | Accepted | Pays Postgres/Redis startup cost once for the whole suite instead of once per test class. |
| Pointing `CacheDegradationIT` at a closed port rather than stopping the shared Redis container | Accepted | Stopping the shared container changes its mapped port on restart and breaks every other test in the run. |

## Verification gap — disclosed, not silently skipped

`mvn verify` could **not** be executed against this suite in the session that assembled this
commit. Root cause, confirmed across five independent attempts (nested-container socket mount,
nested-container TCP `DOCKER_HOST`, running Maven natively with no container nesting at all,
bumping `testcontainers-bom` from 1.19.7 to the current 1.21.3, and forcing the Docker Desktop
backend awake via the CLI immediately before the run): Testcontainers' Java client receives a
sanitized, all-empty `/info` response from this machine's Docker Desktop
(`"com.docker.desktop.address":"npipe://\\.\\pipe\\docker_cli"` in the payload) regardless of
connection path, process nesting, or client library version — while `docker.exe` and plain `curl`
against the same endpoints both receive the real daemon info. That rules out the usual suspects
(socket-forwarding misconfiguration, stale client version, a paused Resource Saver VM) and points
at a Docker Desktop-side restriction on this specific host that only this session's environment
could resolve.

Per the working agreement's own rule ("`mvn test` must pass without Docker... `mvn verify` before
merge"), this suite must be run with a working Docker Desktop configuration before merging. Do not
treat this commit's presence in history as evidence the suite is green — it has not been executed
end-to-end yet.

## Reviewer sign-off

**High-impact: none of the constraints above are database/cache/security changes, but the suite
itself is unverified.** Reviewer: ______________  Date: ____________  Confirmed `mvn verify`
green locally before merge: ☐
