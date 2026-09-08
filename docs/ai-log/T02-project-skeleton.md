# T02 — Project skeleton

## Prompt contract

**Intent.** Spring Boot 3.2 project on Java 17 that boots with PostgreSQL, Redis, Flyway and
Actuator wired, and nothing else.

**Constraints.**
- Java 17, not 21 — the target environment is on 17, so no virtual threads and no Java 21 syntax.
- Maven, not Gradle.
- Unit tests must run without Docker; integration tests may require it.
- No dependency added unless something in the plan uses it.

**Acceptance criteria.** `mvn test` passes on a machine with no Docker. `docker compose up --build`
produces a container that reports healthy.

**Technical context supplied.** `docs/REQUIREMENTS.md` and `docs/PLAN.md`.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| `pom.xml` | Edited | Generated version included `flyway-database-postgresql`, which Boot 3.2's dependency management does not version — the build failed with a missing-version error. Removed; Flyway 9 in the Boot 3.2 BOM handles PostgreSQL from core. |
| Surefire/Failsafe split | Edited | Not generated. Added deliberately so `*Test` runs without Docker and `*IT` runs under `mvn verify`, which is what makes the fast loop fast. |
| `spring-boot-starter-security` | **Rejected** | Suggested as standard. There is no authentication model in this prototype, so it would have added a filter chain to configure around for the sake of four response headers. `SecurityHeadersFilter` sets them in 20 lines instead. |
| `Dockerfile` | Edited | Generated single-stage build shipped the JDK and the Maven cache. Rewritten as multi-stage with a JRE runtime, dependency layer caching, a non-root user, and `MaxRAMPercentage` instead of a fixed `-Xmx` that ignores the cgroup limit. |
| `docker-compose.yml` | Edited | Added healthchecks and `depends_on: condition: service_healthy`. Without them the app raced the database on a cold start and looked like a crash loop. |

## Validation

- `mvn -B test` on a clean checkout, no Docker daemon: passes.
- Compiled against JDK 17 specifically, not JDK 21 with `--release 17`.

## Reviewer sign-off

Not applicable (no high-impact change).
