# T11 — Integration suite verified fully green, and two real bugs found in getting there

## Prompt contract

**Intent.** `docs/ai-log/T07-integration-tests.md` disclosed that `mvn verify`'s Testcontainers
suite could not be executed in this environment: Docker Desktop returned a sanitized `/info`
response to the Testcontainers Java client regardless of connection method. Find a way to actually
run it, rather than leaving the disclosure as the permanent state of the project.

**Constraints.** Fix must be reproducible and documented, not a one-off manual workaround. Prefer
fixes that make the suite portable across environments over fixes that patch around this specific
machine.

## The path to a working Docker daemon

Docker Desktop's Windows integration layer was the actual blocker (see T07), not anything fixable
from the project side. This machine has a real Ubuntu WSL2 distribution, separate from the
`docker-desktop` internal VM Desktop uses. Installed Docker Engine natively inside it
(`docker-ce`, `docker-ce-cli`, `containerd.io` via Docker's official apt repo) — a completely
independent daemon with no Windows Desktop layer in between.

**New problem, immediately:** Testcontainers' bundled `docker-java` client sends a hardcoded API
version (`1.32`) on its initial connectivity ping, regardless of Testcontainers version (confirmed
identical on both 1.19.7 and 1.21.3, the latest available at time of writing). Freshly-installed
Docker Engine 29.8.0 enforces a minimum supported API of 1.40 and rejects the ping outright.
`DOCKER_API_VERSION` as an environment variable does not override this specific hardcoded ping —
it's baked into `docker-java`'s connectivity-check code path.

**Fix.** Pinned Docker Engine in the WSL distro to 26.1.4 (mid-2024), whose lower API floor accepts
the old ping. `apt-mark hold` on `docker-ce`/`docker-ce-cli` so a routine `apt upgrade` doesn't
silently reintroduce the incompatibility. This is environment setup, not a repo change — recorded
here so it's reproducible.

## Bug found #1 — Mockito's inline mock-maker can't self-attach under WSL2

Once Docker connectivity worked, every `*IT` failed identically with
`MockitoInitializationException: Could not initialize inline Byte Buddy mock maker`, bottoming out
in `AttachNotSupportedException: Unable to open socket file /tmp/.java_pid<pid>: target process
doesn't respond within 10500ms`. Neither `-Djdk.attach.allowAttachSelf=true` nor lowering the
kernel's `ptrace_scope` restriction fixed it alone; `-XX:+StartAttachListener` (forcing the JVM's
attach listener thread to start eagerly instead of lazily on signal) got the suite running, but
still depended on a JVM flag and a kernel setting nobody would remember to set.

**Root cause, restated:** none of our own test code uses Mockito — `LinkServiceTest` and friends
use hand-written fakes specifically to avoid it (see `docs/AI-USAGE.md`'s rejection of mocks for
exactly this class of test). The only thing pulling Mockito's static initializer is Spring Boot
Test's own `ResetMocksTestExecutionListener`, which runs unconditionally on every Spring context
test regardless of whether `@MockBean` is used, just to check `MockUtil.isMock()`.

**Fix, the portable version.** Added `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
containing `mock-maker-subclass`. This tells Mockito to use its subclass-based mock maker instead
of the inline Byte Buddy agent — which needs no runtime self-attach at all. Verified this removes
the dependency entirely: reset `ptrace_scope` back to its restrictive default (`1`) and ran
`mvn verify` with zero custom JVM flags — still fully green.

## Bug found #2 — RateLimitIT's shared Redis counter, exposed for the first time

With the above fixed, 18/20 integration tests passed on the first real run; `RateLimitIT` failed
both its tests with `expected: 201, but was: 429`.

**Root cause.** `Containers.java` starts one Redis container for the entire test JVM, shared across
every `*IT` class. `RateLimitIT` runs in its own Spring context with `create-requests-per-minute`
turned down to 5, specifically so its boundary is reachable — but the rate-limit counter itself
lives in that shared Redis, keyed by client identity + time window, not by Spring context. Every
other `*IT` class creates links from the same test-JVM loopback address under the *default* 60/min
limit before `RateLimitIT` runs. By the time `RateLimitIT`'s own requests fire, the shared counter
for that owner+window was already exhausted by unrelated tests. This could not have surfaced before
today: this is the first time the full suite ever ran to completion in one process.

**Fix.** `RateLimitIT` now sends a unique `X-Api-Key` header per test method
(`ClientIdentity.of` honours the header over remote address), giving each test method its own
rate-limit bucket that no other test — in this class or any other — can have touched.

## Outcome

| Item | Disposition | Note |
|------|-------------|------|
| WSL2 native Docker Engine, pinned to 26.1.4 | Accepted (environment setup) | Not a repo change; documented here for reproducibility. |
| `testcontainers.version` 1.19.7 → 1.21.3 | Accepted | Latest available; did not by itself fix the API-version ping (confirmed identical failure on both), but no reason to stay on the older one now that the suite is verified green with the newer one. |
| `-XX:+StartAttachListener` / `-Djdk.attach.allowAttachSelf=true` JVM flags | **Rejected in favour of the mock-maker fix** | Worked, but relied on flags nobody would remember to pass and a kernel setting (`ptrace_scope`) that resets on reboot. The `mockito-extensions` file removes the underlying need entirely. |
| `mockito-extensions/org.mockito.plugins.MockMaker` (`mock-maker-subclass`) | Accepted | Verified fully green with zero custom JVM flags and `ptrace_scope` at its restrictive default. |
| `RateLimitIT` unique `X-Api-Key` per test | Accepted | Real, previously undiscoverable test-isolation bug; now isolated from every other IT class's rate-limit usage. |

## Validation

`mvn verify` from a clean WSL2 Ubuntu shell, no special flags, `ptrace_scope=1` (default):

```
Tests run: 64, Failures: 0, Errors: 0, Skipped: 0   (unit)
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0   (integration)
BUILD SUCCESS
```

This is the first time this project's full test suite — the one `README.md` and
`docs/TESTING.md` describe — has actually been run to completion and gone green.

## Reviewer sign-off

**Not high-impact per the CLAUDE.md list** (no schema, redirect-resolution, validation, or cache
behaviour changed) — this task changed test infrastructure and a test-only dependency version.
Recorded for traceability per the process rule that every change gets a log entry.
