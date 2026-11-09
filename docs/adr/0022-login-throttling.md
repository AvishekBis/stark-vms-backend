# 0022. Login throttling: in-memory failure counters per e-mail and per IP

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** before implementation (decision D1 of 2026-11-07, taken for both assignments; the code does not exist
  yet; this ADR gets file and test references when the change is merged)
- **Implemented:** 2026-11-08 (v1.1) — see "Implementation" at the end; the decision above is unchanged
- **Amended:** 2026-11-08 after a security review — see "Amendment after the security review" at the end (IP from
  the right-most proxy-added entry, locked entries never evicted, attempts reserved before the password check, safe
  logging)
- **Deciders:** project owner

The same decision, with the same number, exists in Assignment 2 (`assignment 2/docs/adr/0022-login-throttling.md`).
This copy describes it for the v1.0 code (v1.1 hardening).

## Context

`POST /api/v1/auth/login` has no rate limit or lockout (finding **NFR-SEC-01**, see
[0007](0007-authentication-stateless-jwt.md) and [0015](0015-non-functional-requirements-strategy.md)):
- online password guessing is unlimited;
- every attempt runs one BCrypt check (`BE/auth/AuthService.java`), also for unknown e-mails (`timingEqualizerHash`,
  on purpose, so timing does not reveal accounts). A burst of requests can saturate the 0.1-CPU free Render instance.

Forces:
- Guideline section 13 (security), section 17.2 ("Security", "Errors": correct status codes, clear messages).
- The free tier runs **one** backend instance. There is no Redis or other shared cache, and adding a service costs
  money and setup time ([0016](0016-deployment-render-and-netlify.md)).
- The login response must not reveal whether an e-mail exists (FR-AUTH-01, generic 401).
- `server.forward-headers-strategy: framework` is already set in `application.yml` ([0016](0016-deployment-render-and-netlify.md)),
  so `ForwardedHeaderFilter` turns Render's `X-Forwarded-For` into `request.getRemoteAddr()`. *(Corrected, see
  Amendment: `framework` trusts client-supplied headers; the setting is now `native`.)*
- All time comes from the injected Asia/Dhaka `Clock` ([0011](0011-time-and-expiry-with-injected-clock.md)).

## Decision

**A new in-memory `LoginAttemptService` in the `auth` package counts failed logins per e-mail and per client IP.
While an e-mail is locked or an IP is blocked, login answers 429 without checking the password.**

**Rules**
- **Per e-mail:** 5 failed logins for one e-mail within the window lock that e-mail for the lockout period.
- **Per IP:** 20 failed logins from one IP within the window block that IP for the lockout period, for every e-mail.
- **Window = lockout length = 15 minutes.** The window starts with the first counted failure of a key; when it has
  passed without reaching the threshold, the count starts again from zero.
- **What counts as a failure:** every login that ends in the generic 401 (wrong password, unknown e-mail, disabled
  user). A 400 from bean validation is not counted.
- **The attempt that reaches the threshold** still gets the normal 401; the next attempt gets 429.
- **While locked or blocked** the password is **not** checked, not even a correct one, and no BCrypt runs. The
  attempt is not counted again, so retries do not extend the lockout.
- **A successful login clears that e-mail's counter**, not the IP counter (a valid account cannot be used to reset
  the IP limit).
- **No account enumeration:** unknown e-mails are counted and locked exactly like existing ones.

**Keys**
- E-mail: `AuthService.normalizeEmail` (trim, `toLowerCase(Locale.ROOT)`).
- IP: a `String` passed from `AuthController` (`request.getRemoteAddr()`); the service never sees
  `HttpServletRequest`.
- `server.forward-headers-strategy=framework` stays set. **A spoofed `X-Forwarded-For` can only evade the per-IP
  limit, never the per-e-mail limit.** *(Corrected, see Amendment: with `framework` a spoofed header could also
  block a chosen IP, and with the old eviction rule free a locked e-mail; the setting is now `native`.)*

**Response while locked or blocked** — **429 Too Many Requests**, header `Retry-After: <seconds>` (remaining lock
time, rounded up, at least 1; the larger value when both keys are locked), Problem Details:
```json
{
  "type": "https://stark-vms/errors/too-many-login-attempts",
  "title": "Too many login attempts",
  "status": 429,
  "detail": "Too many failed login attempts. Try again in 15 minutes.",
  "instance": "/api/v1/auth/login"
}
```
`N` in "Try again in N minutes." is the remaining time in whole minutes, rounded up ("1 minute" when N = 1). No
`errors[]`. `Retry-After` is added to the CORS exposed headers (`BE/config/CorsConfig.java`). New constant
`ApiErrorType.TOO_MANY_LOGIN_ATTEMPTS`, mapped in `GlobalExceptionHandler`.

**Order inside `AuthService.login`:** normalize → check locks (429) → authenticate as today → on failure record the
e-mail and the IP and throw 401 → on success clear the e-mail counter and issue the token. *(Corrected, see
Amendment: the lock check and the count are one atomic "reserve" before the password check.)*

**Configuration** (`app.security.login.*`, validated `@ConfigurationProperties`, start-up fails on a value ≤ 0):

| Key | Default |
|---|---|
| `app.security.login.max-failures-per-email` | `5` |
| `app.security.login.max-failures-per-ip` | `20` |
| `app.security.login.lockout` | `15m` (also the counting window) |

**Memory bound and thread safety:** two `ConcurrentHashMap`s updated with `compute`/`merge`; at most **10,000 keys
per map**; expired entries removed on access; when full, expired entries are purged and then the **oldest** entry is
evicted *(corrected, see Amendment: locked entries are never evicted)*. The start of a lock is logged at WARN with the e-mail or IP, never the password.

**Frontend:** `FE/features/auth/presentation/login_controller.dart` shows the 429 `detail` as the form error (not
"Invalid email or password"). `AuthInterceptor` (`FE/core/network/api_client.dart`) only reacts to 401 and never for
the login request, so 429 does not trigger the 401 → login redirect. `ApiException` gets a message for 429.

**Tests (part of the implementation):** unit tests with a mutable `Clock` (threshold, window reset, lockout end,
success clears the e-mail only, eviction, concurrency); MockMvc (6th attempt → 429 with `Retry-After`, correct
password while locked → 429, unknown e-mail identical, 21 failures from one IP); a login-screen widget test.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Bucket4j (in memory) | A new dependency for two counters; the rule is about *failures* with a reset on success, which needs custom code anyway. |
| Bucket4j / custom limiter on Redis | Correct with several instances and survives restarts, but needs an extra service on Render. Follow-up if the backend ever runs more than one instance. |
| Failure counters in the database (`users.failed_attempts`, `locked_until`) | Only works for existing accounts (enumeration) unless a separate table stores arbitrary e-mails and IPs; a write per failed login on the free database. |
| CAPTCHA after N failures | A third-party script and key in an app that loads none today; does not remove the BCrypt cost of a burst. |
| Limit only per IP / only per e-mail | Per IP alone is evaded by spoofing and does not stop distributed guessing on one account; per e-mail alone lets one IP spray many accounts. |
| A servlet filter in front of the controller | Only `AuthService` knows whether the login failed. |

## Consequences

**Positive**
- Guessing is limited to 5 tries per account and 20 failures per IP per 15 minutes; locked attempts cost no BCrypt.
- No new dependency or service; configurable; testable with the injected `Clock`.

**Negative / accepted trade-offs**
- **State is per instance and lost on restart** (a Render redeploy or spin-down unlocks everything). Accepted: the
  free tier runs one instance.
- Anyone who knows an e-mail can keep it locked (5 wrong passwords every 15 minutes).
- Users behind one NAT address share the IP budget.
- Many unknown e-mails from many IPs can fill the 10,000 slots and evict older entries *(since the Amendment never
  a locked one)*.
- ~~`X-Forwarded-For` spoofing evades the per-IP limit only.~~ Closed by the Amendment.

**Follow-ups**
- A shared store if the backend runs several instances.
- ~~Trust only the proxy-added `X-Forwarded-For` entry~~ — done (Amendment, `native`).
- Update [0015](0015-non-functional-requirements-strategy.md) (NFR-SEC-01) and [0007](0007-authentication-stateless-jwt.md)
  after implementation.

## Related

- Guideline: sections 4, 13, 17.2 (Security, Errors, Tests); FR-AUTH-01.
- NFR findings: **NFR-SEC-01** (closed by this ADR), NFR-MAINT-03 (failed logins logged); NFR-REL-01 is handled by
  [0023](0023-optimistic-locking.md).
- ADRs: [0007](0007-authentication-stateless-jwt.md), [0008](0008-error-format-problem-details.md),
  [0011](0011-time-and-expiry-with-injected-clock.md), [0015](0015-non-functional-requirements-strategy.md),
  [0016](0016-deployment-render-and-netlify.md).
- Files (planned): `BE/auth/LoginAttemptService.java`, `BE/auth/LoginThrottleProperties.java`,
  `BE/auth/AuthService.java`, `BE/auth/AuthController.java`, `BE/common/exception/ApiErrorType.java`,
  `BE/common/exception/GlobalExceptionHandler.java`, `BE/config/CorsConfig.java`,
  `stark-vms-backend/src/main/resources/application.yml`, `FE/features/auth/presentation/login_controller.dart`,
  `FE/core/network/api_exception.dart`, `docs/API_CONTRACT.md` §3 and §6.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.

## Implementation (2026-11-08, v1.1)

Implemented as decided; the code is the same as in Assignment 2 (ported, without the v2.1 user management).
- **Backend:** `auth/LoginAttemptService` (two `ConcurrentHashMap`s; every change of a key is one atomic `compute`,
  writes to a map are serialised so the 10,000-key bound always holds), `auth/LoginThrottleProperties`
  (`app.security.login.*`, start-up fails on values ≤ 0), `AuthService.login(request, clientIp)` (lock check → user
  lookup → BCrypt → count / clear), `AuthController` passes `HttpServletRequest.getRemoteAddr()`,
  `common/exception/TooManyLoginAttemptsException` + `GlobalExceptionHandler` (429, `Retry-After`, no `errors[]`),
  `CorsConfig` exposes `Retry-After`, `application.yml` (`APP_SECURITY_LOGIN_*`; `forward-headers-strategy` kept).
- **Frontend:** `ApiException.isTooManyRequests` and a 429 fallback message; `LoginController` already shows any
  non-401 `detail`, and the 401 interceptor ignores 429 (both now covered by tests).
- **Tests:** `LoginAttemptServiceTest` (12, moving test `Clock`), `AuthServiceTest` (3), `LoginThrottlingApiTest`
  (8, MockMvc on embedded PostgreSQL), two cases in `GlobalExceptionHandlerTest`; frontend
  `test/unit/login_throttling_test.dart` (4) and a 429 case in `test/widget/login_screen_test.dart`.

## Amendment after the security review (2026-11-08)

A security review of the implementation found that two claims above were wrong and that the check and the count
were not atomic. The decision itself (D1: thresholds, keys, 429 response, in-memory state) is unchanged; the
mechanism was corrected as follows. The original text above is kept for the record; where it is wrong it is marked
"corrected, see Amendment".

| Finding | Problem | Correction |
|---|---|---|
| SEC-1 / F2 (a) | `forward-headers-strategy=framework` makes Spring's `ForwardedHeaderFilter` take the **left-most** entry of the client-supplied RFC 7239 `Forwarded: for=` header, or else of `X-Forwarded-For`. So a client could not only *evade* the per-IP limit but also **block a chosen IP** (an office NAT address, the ADMIN's home IP) for 15 minutes with 20 requests, correct passwords included. | `forward-headers-strategy=native`: Tomcat's `RemoteIpValve` reads `X-Forwarded-For` only when the TCP peer is a trusted internal proxy (Spring Boot's default list: 10/8, 172.16/12, 192.168/16, 100.64/10, 169.254/16, loopback; `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` overrides it) and walks it **from the right**, taking the first entry that is not such a proxy: the address Render's proxy appended. `Forwarded` is ignored. Test: `ClientIpApiTest` (real Tomcat on a random port). |
| SEC-2 / F2 (b) | When a map was full, the entry with the oldest window was evicted, even a locked one. With a free IP key, one host could spray 10,000 unknown e-mails and evict the lock of a chosen e-mail, then guess again: the "never evades the per-e-mail limit" claim was false. | A **locked entry is never evicted**. When full, expired entries are purged, then the *unlocked* entry with the fewest failures (oldest first among equals) is evicted, so an e-mail close to its limit outlives sprayed single failures. If every entry is locked, the new key is not tracked in that map (a new e-mail then counts under its IP only). Tests in `LoginAttemptServiceTest`. |
| F1 / SEC-4 | "Check the lock" and "count the failure" were two steps with a BCrypt check (about 100 ms) in between, so requests in parallel (up to the DB pool size) all passed the check: 9 passwords tested instead of 5, and a correct one among them logged in although the e-mail was "already" locked. | **Reserve, then check:** `LoginAttemptService.tryAcquire(email, ip)` refuses while locked and otherwise counts the attempt, in one step under one monitor, *before* the password check. The caller settles it: `failed` (count stays), `succeeded` (e-mail cleared, the IP gets this attempt back) or `release` (both given back, e.g. the database failed before the password was compared). So at most 5 passwords per e-mail and 20 per IP are tested per window, whatever the concurrency. Tests: `LoginAttemptServiceTest.parallelAttemptsNeverGetMoreTriesThanTheLimit`, `AuthServiceTest.parallelLoginsCannotTestMorePasswordsThanTheLimit` (only one `PasswordEncoder.matches` call for 10 parallel logins at count 4). |
| SEC-6 | The lock warning logged the attacker-supplied e-mail as it was; CR/LF inside it could forge log lines (CWE-117). | Control characters (Unicode `Cc`, line and paragraph separators) are replaced by `?` and the key is cut at 100 characters before it is logged. |

**What still holds (residual risk):** a locked e-mail can no longer be freed at all. An e-mail that is close to its
limit (but not locked) can only be pushed out by filling all 10,000 slots with entries at least as close to the
limit, i.e. up to 40,000 failed logins in 15 minutes, which needs about 2,000 real source addresses at 20 failures
each; the per-IP limit counts real source addresses as Render's proxy sees them. If a CDN or a second proxy with a public
address ever sits in front of Render's proxy, its address would be the right-most entry and all its clients would
share one IP budget; then add it with `SERVER_TOMCAT_REMOTEIP_TRUSTED_PROXIES`. The lockout-as-denial-of-service
trade-off for a known e-mail (Consequences) is unchanged.
