# T09 — Refactor: adopt Lombok for constructor/getter/logger boilerplate

## Prompt contract

**Intent.** Reduce hand-written constructor-injection, getter, and logger boilerplate using
Lombok, with no behavioural change anywhere.

**Constraints.**
- No behaviour change. Every existing test must pass unmodified.
- JPA entities (`Link`, `ClickEvent`) are hydrated by Hibernate via reflection through the no-arg
  constructor; anything that makes that fragile is out.
- Don't touch classes whose constructor does real work beyond field assignment.
- Don't rename existing accessor methods that calling code already depends on.

**Acceptance criteria.** `mvn test` green, unit test count unchanged (63). A live
`docker compose up --build` walkthrough (create, redirect, metadata, analytics, disable) behaves
identically to before the refactor.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| `@RequiredArgsConstructor` on `LinkService`, `AnalyticsService`, `UrlValidator`, `JpaLinkStore`, `LinkController`, `RedirectController`, `RateLimitFilter`, `RateLimiter`, `ClickEventListener` | Accepted | All had constructors that did nothing but assign final fields in declaration order; Lombok's generated constructor is byte-for-byte equivalent. |
| `@Slf4j` on `RateLimiter`, `LinkCache`, `ClickEventListener`, `AsyncConfig`, `GlobalExceptionHandler` | Accepted | Replaces `private static final Logger log = LoggerFactory.getLogger(X.class)` with an identical field. |
| `Link`/`ClickEvent` entity fields made `final`, with `@AllArgsConstructor` | **Rejected — self-caught** | First draft made entity fields `final`. Hibernate hydrates entities from the database via reflection on the no-arg constructor; setting `final` fields through reflection is fragile and not guaranteed across JVM configurations. Reverted to non-final fields before running anything, still using `@Getter` + `@NoArgsConstructor(PROTECTED)` + `@AllArgsConstructor`. |
| `LinkCache`'s constructor | **Left manual** | Builds its own `ObjectMapper` (see T08) — that's real logic, not a candidate for `@RequiredArgsConstructor`. |
| `ShortCodeGenerator`'s constructor | **Left manual** | Stores a derived field (`codeLength` extracted from `properties`), not the injected type itself, so there's no straight field assignment for Lombok to generate. |
| DTOs (`CreateLinkRequest`, `LinkResponse`, `StatsResponse`, `ApiError`) | **Left alone** | Already records; Lombok would add nothing. |
| Exception classes (`ApiException` and subclasses) | **Left alone** | `status()`/`code()` are deliberately non-bean-style accessors used as such at every call site (e.g. `GlobalExceptionHandler`). Lombok's `@Getter` would generate `getStatus()`/`getCode()`, forcing a rename that ripples outward for a two-line saving. |

## Validation

- `mvn test` — 63/63 unit tests pass, unchanged.
- Live: rebuilt the full Docker Compose stack and re-ran create → redirect (exercises `Link`
  hydration from Postgres) → metadata (Lombok `@Getter`) → analytics (`ClickEvent` insert via the
  Lombok-generated all-args constructor) → disable (`Link.setStatus`, the one field-level
  `@Setter`). All behaved identically to the pre-refactor baseline captured in T08's validation.

## Reviewer sign-off

**Not high-impact** — no schema, redirect-resolution, input-validation, or cache read/write
*behaviour* changed; `LinkCache`'s constructor body is unchanged, only its declaring class gained
an unrelated `@Slf4j` annotation. No sign-off required per the CLAUDE.md list, but noted here for
traceability per the process rule that every change gets a log entry.
