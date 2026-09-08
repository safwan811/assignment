-- Baseline schema for short links.
--
-- Uniqueness is enforced here rather than in application code: multiple service instances do
-- not share memory, so the database is the only correctness boundary that all of them respect.

CREATE TABLE links (
    id              UUID         PRIMARY KEY,
    short_code      VARCHAR(64)  NOT NULL,
    original_url    VARCHAR(2048) NOT NULL,
    url_fingerprint VARCHAR(64)  NOT NULL,
    dedup_key       VARCHAR(200) NOT NULL,
    owner           VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ
);

-- Serves both the uniqueness invariant and the redirect lookup.
CREATE UNIQUE INDEX ux_links_short_code ON links (short_code);

-- Makes creation idempotent per owner. The key is "<owner>|<fingerprint>" for a normal create,
-- and a random value when the caller explicitly asked for a fresh link (forceNew) or supplied a
-- custom alias. Indirecting through dedup_key keeps uniqueness in the database for every path,
-- instead of having application code decide when the constraint applies.
CREATE UNIQUE INDEX ux_links_dedup_key ON links (dedup_key);

-- Non-unique: supports "which links point at this URL" style queries without constraining them.
CREATE INDEX ix_links_fingerprint_owner ON links (url_fingerprint, owner);

ALTER TABLE links ADD CONSTRAINT ck_links_status CHECK (status IN ('ACTIVE', 'DISABLED'));
