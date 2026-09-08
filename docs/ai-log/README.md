# AI execution log

One file per task. Each records the prompt contract given to the assistant, what came back, what
was changed or rejected, and how the result was validated.

**How to read the disposition field**

| Value | Meaning |
|-------|---------|
| `Accepted` | Used essentially as generated after review |
| `Edited` | Generated then materially changed; the change is described |
| `Rejected` | Generated and not used; the reason is recorded |

**Sign-off.** Entries touching the high-impact list below carry a named reviewer. Anything
unsigned has not been reviewed and must not be merged.

High-impact changes requiring explicit sign-off:

- database migrations and schema changes
- redirect resolution logic
- input validation and other security controls
- cache read, write or invalidation behaviour

**Secure AI usage rules applied throughout**

- No credentials, connection strings, API keys, customer data or internal hostnames in any prompt.
- No proprietary code from other employers pasted as context.
- Generated dependencies are checked against the Maven Central listing before being added; no
  package is added because a model asserted it exists.
- Generated SQL is read in full before it is run; migrations are never applied from a prompt
  response without being read.
