# T05 — Create endpoint, deduplication and validation

## Prompt contract

**Intent.** `POST /api/v1/links` with normalisation, validation, optional custom alias, optional
expiry, and per-caller deduplication.

**Constraints.**
- Concurrent creates of the same URL must converge on one link. No JVM locks.
- Deduplication must be scoped to the caller: tenants must not observe each other's links.
- `forceNew=true` must be able to opt out of deduplication.

**Acceptance criteria.** Concurrency test with 20 threads produces exactly one link. Alias
collisions return 409. SSRF-shaped destinations return 400.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| Check-then-insert deduplication | **Rejected** | Generated first. It passes every single-threaded test and races in production. Replaced with `INSERT … ON CONFLICT DO NOTHING` plus a re-read of the winner. |
| `catch (DataIntegrityViolationException)` retry | **Rejected** | Also generated. In PostgreSQL a constraint violation poisons the surrounding transaction, so the retry cannot proceed in the same transaction — the code would have failed exactly when it was needed. |
| Unique index on `(url_fingerprint, owner)` | Edited → **replaced** | See "Defect found" below. |
| `UrlValidator` private-address blocking | Edited | Generated version called `InetAddress.getByName` on every host, adding an uncontrolled DNS lookup to the create path. Narrowed to IP literals only, with the DNS-rebinding limitation stated explicitly in the class comment and in `docs/RISKS.md` rather than papered over. |
| Bean Validation for URL semantics | **Rejected** | Suggested a `@Pattern` regex on the `url` field. URL validation by regex is a well-known way to be subtly wrong; the rules live in `UrlNormalizer` and `UrlValidator` where they are unit-testable without the web layer. |

## Defect found by a test, not by review

`LinkServiceTest.forceNewBypassesDeduplication` failed on first run.

The design had a unique index on `(url_fingerprint, owner)`. `forceNew` skipped the pre-check but
still hit that index, so the insert conflicted, the race-resolution branch found the existing link,
and `forceNew` silently returned the *original* code. The API had a documented flag that could
never work.

Fix: a `dedup_key` column carrying `<owner>|<fingerprint>` for a normal create and a random value
when dedup is opted out, with the unique index on `dedup_key`. Uniqueness stays in the database on
every code path instead of application code deciding when the constraint applies.

Follow-on decision made during the fix: if the dedup key resolves to a **disabled** link, mint a
fresh key rather than returning the disabled one. Reusing it would silently re-enable something an
operator had switched off.

This is the entry I would point at in review. The contradiction was in the design before any code
was generated; no amount of reading the generated code would have surfaced it. The failing test did.

## Validation

- `LinkServiceTest` — 11 tests across creation.
- `ConcurrentCreationIT` — 20 concurrent creates → one link; 10 concurrent alias creates → one
  winner, the rest 409.
- `LinkLifecycleIT` — SSRF target, reserved alias, alias collision through the real stack.

## Reviewer sign-off

**High-impact: schema change + input validation.** Reviewer: ______________  Date: ____________
