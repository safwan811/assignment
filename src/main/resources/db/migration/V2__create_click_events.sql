-- Analytics storage, added in the brownfield scenario (see docs/scenarios/brownfield.md).
--
-- Raw events rather than counters only: counters cannot answer "clicks per day" or
-- "top referrers" after the fact. Deliberately no IP column; see docs/adr/ADR-003.

CREATE TABLE click_events (
    id                UUID         PRIMARY KEY,
    short_code        VARCHAR(64)  NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    referrer_host     VARCHAR(255) NOT NULL,
    user_agent_family VARCHAR(32)  NOT NULL
);

-- Every analytics query filters by short_code and usually by time, so the composite index
-- covers the time-bucketed queries as well as the plain count.
CREATE INDEX ix_click_events_code_time ON click_events (short_code, occurred_at DESC);

-- No foreign key to links on purpose: this table is append-only and written asynchronously,
-- and an FK check on every insert would couple analytics write throughput to the links table.
