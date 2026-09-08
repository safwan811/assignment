# Scenario 3 — Ambiguous: "make it reliable and make sure it can't be abused"

Two undefined words, no acceptance criteria, no owner present to ask. Normalising this was the
work; the code that followed was the easy part.

## Step 1 — Name the ambiguity instead of guessing past it

"Reliable" could mean any of: survives dependency failure, survives traffic spikes, never loses
data, meets an availability target, degrades predictably. These need different engineering.

"Can't be abused" could mean: bulk creation, malicious destinations, scraping analytics, or
enumerating other people's links. Also different engineering.

## Step 2 — The questions I would have asked

1. Reliable against what — dependency failure, load, or bad data?
2. Is stale or missing analytics acceptable during an incident?
3. Abuse by whom — someone creating a million links, or someone shortening a phishing page?
4. Is there an availability target, or does "reliable" mean "does not fall over on a Tuesday"?
5. Is losing analytics events during a deploy acceptable?
6. Who can disable a link, and how fast must that take effect?

## Step 3 — Assumptions recorded, so the reader can disagree with the premise

| Question | Assumed answer | Basis |
|----------|----------------|-------|
| 1 | Redis is the most likely dependency to fail and the least critical | It is a cache; Postgres is the source of truth |
| 2 | Yes, stale analytics is acceptable | Analytics is best-effort; redirects are not |
| 3 | Both bulk creation and malicious destinations | Rate limiting for one, validation plus disable for the other |
| 4 | "Degrades rather than fails" | A prototype cannot evidence a nines target; claiming one would be dishonest |
| 5 | Yes, and it is documented as a limitation | Fixing it properly means Kafka, which was out of scope |
| 6 | Any caller, effective within seconds | No auth model exists yet; the latency bound is what is testable |

If a reviewer disagrees with an assumption, the disagreement is about the premise, which is
exactly where it should be.

## Step 4 — Decompose into testable properties

| Interpretation | Implementation | Test |
|----------------|----------------|------|
| Survives a cache outage | Try/catch fallback in every `LinkCache` method | `CacheDegradationIT` |
| Cannot be flooded with links | Redis fixed-window counter per caller | `RateLimitIT` |
| Concurrency does not corrupt state | DB constraints, conditional insert | `ConcurrentCreationIT` |
| Links can be revoked | Soft delete → 410, cache evicted both tiers | `LinkLifecycleIT` |
| Links can expire | `expiresAt` evaluated per request against an injected `Clock` | `LinkServiceTest`, `LinkLifecycleIT` |
| Bad destinations rejected | Scheme allowlist, private-address block, length cap | `UrlValidatorTest`, `LinkLifecycleIT` |
| Failures are diagnosable | Correlation id on every request, response and log line | `LinkLifecycleIT` |

Each vague word became a property with a test. That is the whole technique.

## Step 5 — Decisions and their trade-offs

**Rate limiter fails open.** If Redis is down, creation is allowed rather than rejected. A
fail-closed limiter converts a cache outage into a write outage. Creation is not a security
boundary; for login or payments the answer would be the opposite, and that reasoning is in the
class comment so it travels with the code.

**Fixed window, not a token bucket.** Allows up to 2× the limit across a boundary. For abuse
control on link creation that is acceptable, and it costs one `INCR` instead of a Lua script.

**Redirects are not rate limited.** Throttling them would penalise a legitimately popular link.
Abuse of the redirect path is handled by disabling the link, not by throttling its visitors.

**`X-Forwarded-For` is not trusted.** Spoofable unless a proxy overwrites it, so trusting it would
let one client bypass the limit entirely. The consequence — behind a load balancer all callers
share one bucket — is recorded in `RISKS.md` (R5) rather than left to be discovered.

**No circuit breaker.** Correct production hardening, not implemented. Without it a hard-down Redis
adds its connect timeout to every request. Listed as a real gap in `RISKS.md` (R10), not quietly
omitted.

## Step 6 — What was left undone, and said so

`RISKS.md` names the gaps this scenario did not close: no destination reputation checking (R1, the
largest gap), rate limiting defeated by rotating IPs without authentication (R3), the 30-second L1
staleness window on other pods after a disable (R7), no circuit breaker (R10), analytics events
lost on pod kill (R13).

An ambiguous requirement answered without stating what was left open has not really been answered.
