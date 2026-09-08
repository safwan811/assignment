# ADR-003 — Store coarse click attributes, never raw IP or full user agent

**Status:** Accepted
**Decision owner:** engineer (me).

## Context

Click analytics wants to answer: how many clicks, over time, from where, on what. The natural
implementation stores the raw request: IP address, full `User-Agent`, full `Referer`. That
maximises future analytical flexibility.

## Decision

Persist only:

- `short_code`
- `occurred_at`
- `referrer_host` — the host component only, never the path or query
- `user_agent_family` — one of Chrome / Edge / Firefox / Safari / Bot / Other / Unknown

No IP address, in any form, hashed or otherwise. No full user agent string. No geolocation.

## Why

Raw IP plus full user agent is a durable device fingerprint. Storing it turns a link shortener into
a tracking system, which brings personal-data obligations (retention, subject access, deletion)
that this service has no machinery for. The analytics questions we actually committed to answering
do not need it.

The referrer path is dropped for the same reason: referrer URLs routinely carry session tokens,
search terms and personal identifiers in their query strings, and we would be collecting them by
accident.

Minimisation happens **at capture**, in `RedirectController`, not at write time. The raw values
never enter a domain object, so there is no later refactor that accidentally starts persisting
them.

A hashed IP was considered and rejected: with a shared salt it is still a stable identifier, and
with a rotating salt it stops being useful for the aggregates we want anyway.

## Consequences

- No unique-visitor counts and no geographic breakdown. If those are required later they need an
  explicit privacy decision, not a schema tweak.
- Bot traffic is bucketed rather than filtered, so `totalClicks` includes crawlers. Callers can see
  the split in `byUserAgentFamily`. Filtering is a presentation choice we have deliberately left to
  the consumer.
- Nothing in `click_events` is personal data, which keeps retention policy simple.
