# C01 — "Make it reliable" (ambiguous requirement)

## The requirement as received

> "Make it reliable and make sure it can't be abused."

Two undefined words and no acceptance criteria. Normalising this was the actual work; the code was
the easy part.

## Questions I would have asked a product owner

1. Reliable against what — dependency failure, traffic spikes, or bad data?
2. Is stale analytics acceptable during an incident, or must every click be counted?
3. Abuse by whom — someone creating millions of links, or someone shortening a phishing page?
4. Availability target? Does "reliable" mean five nines or "does not fall over on a Tuesday"?
5. Is losing analytics events during a deploy acceptable?

## Answers assumed, and why

| Question | Assumption | Basis |
|----------|------------|-------|
| Failure mode | Redis is the most likely dependency to fail, and the least critical | It is a cache; PostgreSQL is the source of truth |
| Stale analytics | Acceptable | Analytics is best-effort; redirects are not |
| Abuse | Both bulk creation and malicious destinations | Rate limiting for the first, validation and disable for the second |
| Availability | "Degrades rather than fails" | An interview prototype cannot evidence a nines target, so claiming one would be dishonest |
| Event loss | Acceptable at prototype scale, documented as a limitation | Fixing it properly means Kafka, which was out of scope |

## What "reliable" was decomposed into

| Interpretation | Implementation | Test |
|----------------|----------------|------|
| Survives a cache outage | Try/catch fallback to PostgreSQL in every `LinkCache` method | `CacheDegradationIT` |
| Cannot be flooded with links | Redis fixed-window counter, per caller | `RateLimitIT` |
| Concurrent requests do not corrupt state | DB constraints, conditional insert | `ConcurrentCreationIT` |
| Links can be revoked and expire | Soft delete + `expiresAt`, both returning 410 | `LinkLifecycleIT`, `LinkServiceTest` |
| Bad destinations rejected | Scheme allowlist, private-address block, length cap | `UrlValidatorTest`, `LinkLifecycleIT` |
| Failures are diagnosable | Correlation id on every request and log line | `LinkLifecycleIT` |

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| Fail-closed rate limiter | **Rejected** | Generated version returned 429 when Redis was unavailable. That converts a cache outage into a write outage. Creation is not a security boundary, so it fails open, and the reasoning is in the class comment. For login or payments the answer would be the opposite. |
| Sliding-window / token bucket | **Rejected** | Suggested as more accurate. A fixed window allows up to 2× the limit across a boundary, which is fine for abuse control on link creation, and costs one `INCR` instead of a Lua script. |
| Rate limiting redirects too | **Rejected** | Would penalise a legitimately popular link. Abuse of the redirect path is handled by disabling the link, not by throttling its visitors. |
| Resilience4j circuit breaker | **Rejected for scope** | Correct production hardening — without it a hard-down Redis adds its connect timeout to every request. Not implemented, and listed as a real gap in `docs/RISKS.md` rather than quietly omitted. |
| Trusting `X-Forwarded-For` | **Rejected** | Generated code read it for the client IP. Trivially spoofed unless a proxy overwrites it, so it would let one client bypass the limit entirely. Uses `getRemoteAddr()` with the deployment caveat documented. |

## Reviewer sign-off

**High-impact: security control.** Reviewer: ______________  Date: ____________
