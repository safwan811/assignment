# Before you submit — do these yourself

This project was assembled with heavy AI assistance, which is the point of the assignment. What
follows is the part that cannot be delegated.

## 1. Run it end to end

```bash
docker compose up --build
```

Then work through every `curl` in `README.md` and confirm the output matches. If anything differs,
fix the README — a broken example in the first thing a reviewer reads is expensive.

## 2. Run the integration tests

```bash
mvn verify
```

Requires a Docker daemon. **Done: 64 unit tests + 20 integration tests, all green** — see
`docs/ai-log/T11-integration-tests-verified-green.md` for what that took (a Docker Desktop Windows
integration issue meant this needed a native Docker Engine in WSL2, plus two real bugs found along
the way: a Mockito JVM self-attach failure, fixed properly rather than papered over with JVM flags,
and a test-isolation bug in `RateLimitIT` that could only have been found by actually running the
full suite to completion). If you run this on a different machine and it fails, don't assume the
suite is broken — check whether your Docker setup can actually reach a real daemon first (T07 has
the diagnostic steps).

## 3. Read the AI logs and make them yours

`docs/ai-log/` is written in the first person. Every rejection in it is a decision you will be
asked to defend out loud. For each one, either:

- confirm you agree and could argue it under questioning, or
- disagree, change the code, and rewrite the entry.

The ones most likely to be probed:

- Why random codes rather than a sequence? (ADR-001)
- Why preserve query parameters when the sample submission strips them? (ADR-002)
- Why 302 rather than 301? (ADR-004)
- Why does the rate limiter fail open? (`ai-log/C01`)
- Why no Kafka? (`ENGINEERING-SUMMARY.md` §5)
- Walk me through the `forceNew` defect. (`ai-log/T05`, `ENGINEERING-SUMMARY.md` §7)

## 4. Sign-off lines

`docs/ai-log/` entries for high-impact changes carry `Reviewer: ______`. Either put your name there
because you have reviewed that area, or leave them blank and add one line to the engineering
summary saying this was a solo prototype with no second reviewer. Do not invent a reviewer.

**Currently unsigned, both need a decision before submitting:**
- `T08-jackson-objectmapper-fix.md` — cache read/write behaviour
- `T10-404-not-500-and-postman-hardening.md` — input handling on every unrouted request

## 5. Commit in stages, not one commit

**Done.** `COMMIT-GUIDE.md` had the staged sequence and it was followed — `git log --oneline` shows
18 commits (the original 12-commit skeleton plus fixes/refactors found and made afterward, each
with its own ai-log entry), not one squashed "Final Commit". The `dedup_key` defect and its fix are
two separate, isolated commits so the history shows the bug being caught, not just the end state.

## 6. Final sweep

- [x] Every `curl` in the README produces the documented output (walked through it live via
      `docker compose up --build`)
- [x] `mvn test` passes (64/64)
- [x] `mvn verify` passes (64 unit + 20 integration, 0 failures — see T11)
- [x] No claim in `docs/` refers to a test that does not exist
- [x] No latency or throughput number appears anywhere (none was measured)
- [ ] Sign-off lines are either filled honestly or explained — **two still open, see §4**
- [x] `git log` shows staged progress, not one commit
