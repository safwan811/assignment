-- Baseline schema for short links.
--
-- Uniqueness is enforced here rather than in application code: multiple service instances do
-- not share memory, so the database is the only correctness boundary that all of them respect.

CREATE TABLE links (
    id              UUID         PRIMARY KEY,
    short_code      VARCHAR(64)  NOT NULL,
    original_url    VARCHAR(2048) NOT NULL,
    url_fingerprint VARCHAR(64)  NOT NULL,
    owner           VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ
);

-- Serves both the uniqueness invariant and the redirect lookup.
CREATE UNIQUE INDEX ux_links_short_code ON links (short_code);

-- Makes creation idempotent per owner: the same (url_fingerprint, owner) pair can only produce
-- one row, so a concurrent duplicate create loses the insert and re-reads the winner instead of
-- racing it.
CREATE UNIQUE INDEX ux_links_fingerprint_owner ON links (url_fingerprint, owner);

ALTER TABLE links ADD CONSTRAINT ck_links_status CHECK (status IN ('ACTIVE', 'DISABLED'));
