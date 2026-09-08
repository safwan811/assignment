# ADR-002 — Preserve the query string when normalising URLs

**Status:** Accepted
**Decision owner:** engineer (me).

## Context

Normalisation decides two things at once: what the user is redirected to, and which URLs count as
"the same" for deduplication. The contested part is the query string.

Stripping query parameters gives cleaner deduplication — `example.com/p?utm_source=email` and
`example.com/p?utm_source=twitter` become one link with one set of analytics, which is what a
marketing team often wants.

## Decision

Canonicalise scheme and host to lowercase, drop default ports (`:80` on http, `:443` on https),
normalise `.` and `..` path segments, drop the fragment. **Keep the query string, byte for byte.**

## Why

The query string usually selects the destination. `example.com/product?id=100` and
`example.com/product?id=250` are different products. Stripping the query would send a user who
clicked a link for product 100 to a bare product page, and it would do so silently — the response
is a perfectly valid 302 to a perfectly valid page, just the wrong one. That class of bug is
nearly invisible in testing and very visible to customers.

I considered the middle path of stripping a known tracking-parameter allowlist (`utm_*`, `fbclid`,
`gclid`). Rejected for now: it is a policy that needs an owner and a maintained list, and getting
it wrong breaks destinations in exactly the way above. It is a reasonable future feature behind an
explicit per-request flag.

The fragment is dropped because it is never transmitted to the server; keeping it in the canonical
form would create spurious distinct links with identical server-side behaviour.

## Consequences

- Two URLs differing only in tracking parameters produce two links with separate analytics. A
  campaign wanting a single aggregate must shorten one canonical URL. This is stated in
  `README.md` and asserted by `UrlNormalizerTest.queryParamsAffectIdentity`.
- Deduplication is narrower than it could be, so slightly more rows.
- The redirect target always matches what the caller supplied, modulo case and default ports.
