## What changed and why

<!-- One or two sentences. Link the ai-log entry if this came from an AI-assisted task. -->

## High-impact checklist

Check any that apply to this PR. Anything checked needs a named reviewer sign-off recorded in
the corresponding `docs/ai-log/` entry before merge (see `CLAUDE.md`).

- [ ] Database migration or schema change
- [ ] Redirect resolution logic
- [ ] Input validation or another security control
- [ ] Cache read, write, or invalidation behaviour

Reviewer: ______________  Date: ____________

## Quality gates

- [ ] `mvn test` passes (no Docker required)
- [ ] `mvn verify` passes (Docker required) — required before merge, not before this PR is opened
- [ ] New behaviour has a test that fails without the change
- [ ] No credentials, connection strings, customer data, or internal hostnames introduced
- [ ] Any new dependency verified to exist on Maven Central

## Notes for the reviewer

<!-- Anything that isn't obvious from the diff: rejected alternatives, known limitations,
     follow-up work intentionally deferred. -->
