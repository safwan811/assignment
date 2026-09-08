# T03 — Domain model and schema

## Prompt contract

**Intent.** `links` table and JPA entity supporting create, redirect, expiry and soft delete.

**Constraints.**
- Flyway owns the schema; Hibernate is `ddl-auto: validate` and never generates DDL.
- Uniqueness must be enforced by the database, not by application code — instances do not share
  memory.
- Timestamps are `TIMESTAMPTZ` and UTC throughout.

**Acceptance criteria.** Migration applies cleanly. Every uniqueness claim in the architecture doc
corresponds to an actual index.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| `links` migration | Edited | Generated version had `original_url TEXT` with a unique index on it. Changed to `VARCHAR(2048)` plus a `url_fingerprint` SHA-256 column: PostgreSQL btree entries are capped around 2704 bytes, so a unique index directly on a long URL is a latent production failure that would not appear in testing with short URLs. |
| `status` column | Edited | Added a `CHECK` constraint. The generated schema relied on the Java enum alone, which does not stop anything writing to the table outside the application. |
| Soft delete via `deleted_at` | **Rejected** | Suggested. Used an explicit `status` enum instead: a redirect needs to distinguish "never existed" (404) from "no longer active" (410), and a nullable timestamp encodes that less clearly than a state does. |
| `Link` entity | Edited | Removed generated Lombok annotations (not a project dependency) and the generated `@Data`-style setters on every field. Only `status` is mutable; everything else is set at construction. |

## Validation

- `mvn verify` applies the migration against a real PostgreSQL 16 container.
- `ddl-auto: validate` means a drift between entity and migration fails startup rather than being
  silently reconciled.

## Reviewer sign-off

**High-impact: schema change.** Reviewer: ______________  Date: ____________
