# T04 — Short code generation

## Prompt contract

**Intent.** Generate short codes for new links.

**Constraints.** Codes must not be enumerable across tenants. Length configurable. No dependence on
a global sequence, so the table can be sharded later.

**Acceptance criteria.** 10,000 draws produce ~10,000 distinct values and match `[A-Za-z0-9]{n}`.
Collision handling has a defined, bounded failure mode.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| Base62 encoding of a DB sequence | **Rejected** | First suggestion, and the better option on pure efficiency. Rejected because sequential codes let anyone walk from their own link to other tenants' links. Recorded in ADR-001. |
| `Random` instead of `SecureRandom` | **Rejected** | A predictable PRNG defeats the entire point of choosing randomness over a sequence. |
| `ShortCodeGenerator` | Accepted | `SecureRandom`, 62-char alphabet, configurable length. |
| Retry loop | Edited | Generated version retried indefinitely on collision. Bounded to `max-collision-retries` then 503: an exhausted keyspace should fail visibly, not spin. Test added for the exhaustion path. |

## Validation

`ShortCodeGeneratorTest` (3 tests): alphabet and length, non-enumerability across 10k draws,
configurable length. `LinkServiceTest.retriesOnCollision` and `.failsClosedAfterRepeatedCollisions`
cover the retry behaviour using a scripted generator, so the test is deterministic rather than
waiting for `SecureRandom` to repeat itself.

## Reviewer sign-off

Not applicable.
