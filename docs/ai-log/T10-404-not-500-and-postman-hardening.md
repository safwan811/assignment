# T10 — Fix: unrouted paths returned 500, and Postman collection hardening

## Prompt contract

**Intent.** While hardening the Postman collection (adding a real dedup/`forceNew` regression
check, since the assignment PDF explicitly grades "realism/quality of outputs"), running the
collection surfaced two independent defects — one in the app, one in the collection itself. Fix
both and prove the fix with a real, repeatable run rather than a single lucky pass.

**Constraints.** Same as always: a test that fails without the change; verify live, not just via
unit tests, since both defects were only found by actually exercising the running service
end-to-end with real tooling (Newman), not by reading the code.

## Defect 1 — `GET /` (or any unrouted path) returned 500, not 404

**How it was found.** Running the Postman collection's `Links` and `Redirect` folders
independently (skipping folders in between) left `{{shortCode}}` empty for one run, which hit
`GET /`. That returned `{"code":"INTERNAL_ERROR", ...}` with a `500`, not the 404 a reviewer would
expect for "this route doesn't exist."

**Root cause.** `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)`
intercepts every unhandled exception, including Spring's own `NoResourceFoundException` — which
Spring's own default handling would have turned into a 404. The catch-all fired first and mapped
it to `INTERNAL_ERROR` / 500 instead.

**Fix.** Added an explicit `@ExceptionHandler(NoResourceFoundException.class)` returning 404 with
`code: "NOT_FOUND"`, ahead of the catch-all.

## Defect 2 — the Postman collection's own dedup/`forceNew`/redirect tests were flaky

Three separate issues, all in the collection, not the app:

| Issue | Root cause | Fix |
|-------|-----------|-----|
| Dedup/`forceNew` regression tests failed on reruns | Both new tests posted the same hardcoded URL every run. Once that URL's link was disabled even once (in an earlier run), `LinkService.create` permanently mints a fresh `unique\|` dedup key for that owner+URL forever — see "Discovered behaviour" below. | Generate a fresh URL per collection run (`https://example.com/postman-dedup-<timestamp>`) in a pre-request script on "Create Short Link", referenced by all three related requests. |
| `Follow Short Link` intermittently asserted the wrong status | Postman/Newman follow redirects by default. The request followed the 302 all the way to `example.com`'s own 404 page for an arbitrary path, so `pm.response.code` was neither 200 nor the original 302 — the test's `if (code !== 200) expect 302` logic had no branch for "followed to something else entirely." | Set `protocolProfileBehavior.followRedirects: false` on the request so the test inspects the raw 302 directly, and simplified the assertion accordingly. |
| A full sequential collection run made `Follow Short Link` legitimately 410 | `Disable Link` was the last item in the `Links` folder, which runs *before* the `Redirect` folder in a full collection run — so by the time the redirect was followed, the link was already disabled. Correct app behaviour, wrong collection ordering. | Moved `Disable Link` into a new `Cleanup` folder at the very end of the collection, after `Redirect`/`Actuator`/`Error examples`, and added a `Confirm disabled link is 410` request after it. |

## Discovered behaviour worth flagging separately

Investigating the flaky dedup test surfaced a real, previously undocumented consequence of the
`forceNew`/`dedup_key` design (`docs/ai-log/T05-create-endpoint.md`): once a URL's link has been
disabled **once** for a given owner, `LinkService.create` will never again produce a deterministic
dedup key for that owner+URL pair — every future create call mints a fresh `unique|...` key instead,
permanently. This is a direct, intended consequence of "don't silently re-enable a disabled link"
(T05), but nobody had previously written down that the practical effect is *permanent* dedup loss
for that URL, not a one-time bypass. Recommend a `docs/RISKS.md` entry; not added here because it's
a documentation gap about existing behaviour, not something this task was scoped to change.

## Validation

- `mvn test` — 64/64 unit tests pass, including a new
  `GlobalExceptionHandlerTest.unroutedPathIsNotFoundNotInternalError`.
- Live: rebuilt the full Docker Compose stack, confirmed `GET /` → `404 {"code":"NOT_FOUND",...}`.
- Live: ran the full Postman collection via Newman **three consecutive times** with no `docker
  compose down` in between — 15 requests, 13 assertions, 0 failures, every run. This specifically
  proves the collection is now safe to rerun against a database with accumulated history, which the
  first version was not.

## Reviewer sign-off

**High-impact: input handling on every unrouted request (redirect-resolution-adjacent).**
Reviewer: ______________  Date: ____________
