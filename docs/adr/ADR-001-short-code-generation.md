# ADR-001 — Random Base62 codes rather than an encoded database sequence

**Status:** Accepted
**Decision owner:** engineer (me). AI generated the option comparison; the choice and its
justification are mine.

## Context

Every short link needs a code. The obvious options:

1. **Base62-encode a database sequence.** Collision-free by construction, shortest possible codes,
   one round trip.
2. **Hash the URL and truncate.** Deterministic, but truncation reintroduces collisions and the
   code leaks nothing useful anyway.
3. **Random Base62 from `SecureRandom`.** Unpredictable, needs a uniqueness check.

## Decision

Option 3. Seven characters from a 62-character alphabet, uniqueness enforced by the `short_code`
unique index, with a bounded retry loop (5 attempts) and a 503 if all of them collide.

## Why

The sequence option is genuinely better on paper — shorter codes, no retries — and I rejected it
for one reason: it makes every link on the platform enumerable. Anyone who receives
`example.com/aB3` can walk to `aB2` and `aB4` and read other tenants' destinations. For a shared
shortener that is a privacy failure, not a theoretical one.

The cost of randomness is a uniqueness check, and that check is the database unique constraint,
which we needed regardless: multiple instances do not share memory, so the constraint is the only
correctness boundary they all respect. So the "extra" cost is close to zero.

Seven characters gives roughly 3.5 × 10¹² codes. At prototype volume the collision probability per
insert is negligible, and the retry loop handles the tail. Five retries then 503 rather than an
unbounded loop: if we are colliding five times in a row, the keyspace is exhausted and the correct
response is to fail visibly, not to spin.

## Consequences

- Codes are one or two characters longer than a sequence would produce. Acceptable.
- A collision costs an extra insert attempt. Rare, bounded, and tested
  (`LinkServiceTest.retriesOnCollision`).
- Keyspace exhaustion has a defined failure mode (503) rather than an undefined one (infinite loop).
- If code length ever needs to grow, `shortener.code-length` is configuration, and existing codes
  keep working because nothing derives length from the code.
