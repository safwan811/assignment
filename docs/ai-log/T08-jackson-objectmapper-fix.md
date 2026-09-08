# T08 — Fix: Redis ObjectMapper bean shadowing Boot's MVC ObjectMapper

## Prompt contract

**Intent.** During an end-to-end review of the working prototype against the assignment brief, a
live `POST /api/v1/links` response returned `"createdAt":1788839943.263325000` instead of the
documented ISO-8601 string, despite `spring.jackson.serialization.write-dates-as-timestamps: false`
being set in `application.yml`. Find the root cause and fix it.

**Constraints.**
- Fix must not change cache correctness behaviour — this is a serialisation defect, not a caching
  one.
- Must not reintroduce the same class of bug (a component's private serialisation needs leaking
  into the whole application) for any other bean.

**Acceptance criteria.** A rebuilt container returns `createdAt` as an ISO-8601 string. All 63
existing unit tests remain green, unchanged.

## Root cause

`AppConfig.java` defined `@Bean ObjectMapper redisObjectMapper()` as a bare
`new ObjectMapper().registerModule(new JavaTimeModule())`, intended only for `LinkCache`'s Redis
serialisation. Spring Boot's `JacksonAutoConfiguration` only creates its own properly-configured
`ObjectMapper` (the one that reads `spring.jackson.*` properties) when **no** `ObjectMapper` bean
already exists in the context — regardless of that bean's name or intended scope. Because this was
the only `ObjectMapper` bean, it silently became the one Spring MVC's
`MappingJackson2HttpMessageConverter` used for every JSON HTTP response, and it defaults to
`WRITE_DATES_AS_TIMESTAMPS = true`.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| `@Bean ObjectMapper redisObjectMapper()` in `AppConfig` | **Removed** | Exposing any `ObjectMapper` as a bean shadows Boot's auto-configured one application-wide. There is no way to scope a bean by name against `@ConditionalOnMissingBean(ObjectMapper.class)`. |
| `LinkCache`'s serialisation needs | **Moved in-line** | `LinkCache` now builds `new ObjectMapper().registerModule(new JavaTimeModule())` itself in its constructor, never registering it with Spring. It was the only consumer, so there is no seam lost. |

## Validation

- `mvn test` — all 63 unit tests pass unchanged (no test exercised the JSON date format directly,
  which is itself worth noting: this defect shipped past the existing suite entirely and was only
  caught by manually walking the README's own curl examples against a running container).
- Live: rebuilt `docker compose up --build`, ran `POST /api/v1/links`, confirmed
  `"createdAt":"2026-09-08T04:03:53.544505Z"`.
- Live: re-ran the full create → redirect → metadata → analytics → disable walkthrough after the
  fix to confirm no other JSON shape regressed.

## Reviewer sign-off

**High-impact: cache read/write behaviour (CLAUDE.md).** Although the defect itself was a
serialisation bug, the fix changes `LinkCache`'s constructor, which is on the cache-write path.
Reviewer: ______________  Date: ____________
