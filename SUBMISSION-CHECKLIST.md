# Before you submit — do these yourself

This project was assembled with heavy AI assistance, which is the point of the assignment. What
follows is the part that cannot be delegated.

## 1. Run it end to end

```bash
docker compose up --build
```

Then work through every `curl` in `README.md` and confirm the output matches. If anything differs,
fix the README — a broken example in the first thing a reviewer reads is expensive.

## 2. Run the integration tests

```bash
mvn verify
```

Requires a Docker daemon. **The unit tests (63) have been run and pass. The integration tests
compile but have never been executed.** If any fail, fix them before submitting — `docs/TESTING.md`
claims they pass, and a claim without evidence is the exact failure mode called out in the review
of the sample repository.

## 3. Read the AI logs and make them yours

`docs/ai-log/` is written in the first person. Every rejection in it is a decision you will be
asked to defend out loud. For each one, either:

- confirm you agree and could argue it under questioning, or
- disagree, change the code, and rewrite the entry.

The ones most likely to be probed:

- Why random codes rather than a sequence? (ADR-001)
- Why preserve query parameters when the sample submission strips them? (ADR-002)
- Why 302 rather than 301? (ADR-004)
- Why does the rate limiter fail open? (`ai-log/C01`)
- Why no Kafka? (`ENGINEERING-SUMMARY.md` §5)
- Walk me through the `forceNew` defect. (`ai-log/T05`, `ENGINEERING-SUMMARY.md` §7)

## 4. Sign-off lines

`docs/ai-log/` entries for high-impact changes carry `Reviewer: ______`. Either put your name there
because you have reviewed that area, or leave them blank and add one line to the engineering
summary saying this was a solo prototype with no second reviewer. Do not invent a reviewer.

## 5. Commit in stages, not one commit

The sample repository had a single commit called "Final Commit", which shows no process. Suggested
sequence, matching the scenario documents:

```bash
git init
git add docs/REQUIREMENTS.md docs/PLAN.md CLAUDE.md
git commit -m "docs: requirement interpretation, task decomposition, working agreement"

git add pom.xml Dockerfile docker-compose.yml .gitignore .github
git commit -m "build: Spring Boot 3.2 / Java 17 skeleton with Postgres, Redis, Flyway"

git add src/main/.../domain src/main/.../repository src/main/resources/db/migration/V1*
git add docs/adr/ADR-001* docs/adr/ADR-002*
git commit -m "feat: links schema and short code generation (ADR-001, ADR-002)"

# ... create endpoint, redirect + cache, then:
git commit -m "fix: forceNew could never work against the unique index; introduce dedup_key"

# ... analytics (brownfield), reliability (ambiguous), then docs
```

The `dedup_key` commit is worth isolating so the defect and its fix are visible in the history.

## 6. Final sweep

- [ ] Every `curl` in the README produces the documented output
- [ ] `mvn test` passes
- [ ] `mvn verify` passes
- [ ] No claim in `docs/` refers to a test that does not exist
- [ ] No latency or throughput number appears anywhere (none was measured)
- [ ] Sign-off lines are either filled honestly or explained
- [ ] `git log` shows staged progress, not one commit
