# 0022. Login throttling: in-memory failure counters per e-mail and per IP

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** before implementation (decision D1 of 2026-11-07; the code does not exist yet; this ADR gets file
  and test references when the change is merged)
- **Implemented:** 2026-11-08 (v2.1). **Amended:** 2026-11-08 after a security review — see "Amendment after the
  security review" below (IP from the right-most proxy-added entry, locked entries never evicted, attempts reserved
  before the password check, safe logging, wrong current passwords throttled)
- **Deciders:** project owner

## Context

`POST /api/v1/auth/login` has no rate limit or lockout (finding **NFR-SEC-01**, see
[0007](0007-authentication-stateless-jwt.md) and [0015](0015-non-functional-requirements-strategy.md)):
- online password guessing is unlimited;
- every attempt runs one BCrypt check (`BE/auth/AuthService.java`), also for unknown e-mails (`timingEqualizerHash`,
  on purpose, so timing does not reveal accounts). A burst of requests can saturate the 0.1-CPU free Render instance.

[0024](0024-admin-user-management.md) adds user management, so there will be more accounts to guess.

Forces:
- Guideline section 13 (security), section 17.2 ("Security", "Errors": correct status codes, clear messages).
- The free tier runs **one** backend instance. There is no Redis or other shared cache in the stack, and adding a
  service costs money and setup time ([0016](0016-deployment-render-and-netlify.md)).
- The login response must not reveal whether an e-mail exists (FR-AUTH-01, generic 401).
- `server.forward-headers-strategy: framework` is already set in `application.yml` ([0016](0016-deployment-render-and-netlify.md)),
  so `ForwardedHeaderFilter` already turns Render's `X-Forwarded-For` into `request.getRemoteAddr()`.
  *(Corrected, see Amendment: `framework` trusts client-supplied headers; the setting is now `native`.)*
- All time comes from the injected Asia/Dhaka `Clock` ([0011](0011-time-and-expiry-with-injected-clock.md)).

## Decision

**A new in-memory `LoginAttemptService` in the `auth` package counts failed logins per e-mail and per client IP.
While an e-mail is locked or an IP is blocked, login answers 429 without checking the password.**

**Rules**
- **Per e-mail:** 5 failed logins for one e-mail within the window lock that e-mail for the lockout period.
- **Per IP:** 20 failed logins from one IP within the window block that IP for the lockout period, for every e-mail.
- **Window = lockout length = 15 minutes.** The window starts with the first counted failure of a key; when it has
  passed without reaching the threshold, the count starts again from zero.
- **What counts as a failure:** every login that ends in the generic 401: wrong password, unknown e-mail, disabled
  user. A 400 (blank or too long field) is rejected by bean validation before the service runs and is not counted.
- **The attempt that reaches the threshold** is still answered with the normal 401. The next attempt gets 429.
- **While locked or blocked** the password is **not** checked, not even a correct one, and no BCrypt runs (this also
  removes the CPU cost of a burst). The attempt is not counted again, so the lockout is not extended by retries.
- **A successful login clears that e-mail's counter.** It does not clear the IP counter, so an attacker who owns one
  valid account cannot reset the IP limit by logging in with it between guesses.
- **No account enumeration:** unknown e-mails are counted and locked exactly like existing ones, with the same
  responses and the same timing.

**Keys**
- E-mail key: `AuthService.normalizeEmail` (trim, then `toLowerCase(Locale.ROOT)`), the same normalization as login.
- IP key: a `String` passed from `AuthController` (`request.getRemoteAddr()`). The service never sees
  `HttpServletRequest`, so it stays a plain unit-testable class.
- `server.forward-headers-strategy=framework` stays set (it already is), so behind Render the IP is the client
  address from `X-Forwarded-For`. That header is client-supplied in its left-most entry, so **a spoofed
  `X-Forwarded-For` can only evade the per-IP limit. It can never evade the per-e-mail limit**, which is keyed on the
  request body. *(Corrected, see Amendment: with `framework` a spoofed header could also block a chosen IP, and with
  the old eviction rule it could free a locked e-mail.)*

**Response while locked or blocked**
- **429 Too Many Requests**, header `Retry-After: <seconds>` (the remaining lock time, rounded up, at least 1; when
  both the e-mail and the IP are locked, the larger value).
- Problem Details:
  ```json
  {
    "type": "https://stark-vms/errors/too-many-login-attempts",
    "title": "Too many login attempts",
    "status": 429,
    "detail": "Too many failed login attempts. Try again in 15 minutes.",
    "instance": "/api/v1/auth/login"
  }
  ```
  `N` in "Try again in N minutes." is the remaining time in whole minutes, rounded up ("1 minute" when N = 1).
  No `errors[]` array. The body is identical for existing and unknown e-mails.
- `Retry-After` is added to the CORS exposed headers (`BE/config/CorsConfig.java`) so the web client may read it; the
  UI itself shows the `detail` text.
- New constant `ApiErrorType.TOO_MANY_LOGIN_ATTEMPTS` and an exception (for example
  `TooManyLoginAttemptsException(Duration retryAfter)`) mapped in `GlobalExceptionHandler`.

**Order inside `AuthService.login`**
1. Normalize the e-mail.
2. Ask `LoginAttemptService` whether the e-mail or the IP is locked → 429.
3. Authenticate as today (BCrypt, `timingEqualizerHash` for unknown e-mails).
4. On failure: record a failure for the e-mail and the IP, then throw the generic 401.
5. On success: clear the e-mail's counter and issue the token.

*(Corrected, see Amendment: steps 2 and 4 are one atomic "reserve" before step 3.)*

**Configuration** (`app.security.login.*` in `application.yml`, bound to a validated `@ConfigurationProperties`
record; start-up fails on a value ≤ 0):

| Key | Default |
|---|---|
| `app.security.login.max-failures-per-email` | `5` |
| `app.security.login.max-failures-per-ip` | `20` |
| `app.security.login.lockout` | `15m` (also the counting window) |

**Memory bound and thread safety**
- Two `ConcurrentHashMap`s (e-mail → entry, IP → entry). An entry holds the failure count, the window start and
  `lockedUntil`. All updates use `compute`/`merge`, so concurrent failures for one key are never lost.
- At most **10,000 tracked keys per map** (a constant). Expired entries (window passed and not locked, or lock
  ended) are removed when they are accessed. When a map is full, expired entries are purged first and then the
  **oldest** entry (earliest window start) is evicted. *(Corrected, see Amendment: locked entries are never evicted.)*
- Locking start is logged at WARN with the normalized e-mail or the IP (never the password), which also covers the
  "log failed logins" part of NFR-MAINT-03.

**Frontend**
- `FE/features/auth/presentation/login_controller.dart` / `login_screen.dart`: a 429 shows the server's `detail`
  ("Too many failed login attempts. Try again in N minutes.") as the form error, not "Invalid email or password".
- 429 does not trigger the 401 → login redirect: `AuthInterceptor` (`FE/core/network/api_client.dart`) only reacts to
  401, and never for the login request. `ApiException` gets a message for 429 for other callers.

**Tests (part of the implementation)**
- Unit tests of `LoginAttemptService` with a mutable test `Clock`: threshold, window reset, lockout end, success
  clears the e-mail only, eviction at 10,000 keys, concurrent failures (`ExecutorService`).
- MockMvc tests: 6th attempt → 429 with `Retry-After` and Problem Details; correct password while locked → 429;
  unknown e-mail behaves the same; 21st failure from one IP across different e-mails → 429.
- Widget test: the login screen shows the 429 detail.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Bucket4j (in memory) | A new dependency for two counters; token buckets limit *requests*, but the rule here is "failures", with a success resetting the e-mail counter, which needs custom code around Bucket4j anyway. |
| Bucket4j or a custom limiter on Redis (shared store) | Correct with several instances and survives restarts, but needs a paid or extra service on Render and a network call on every login. Kept as the follow-up if the backend ever runs more than one instance. |
| Failure counters stored in the database (`users.failed_attempts`, `locked_until`) | Works only for existing accounts, so an unknown e-mail would behave differently (account enumeration) unless a separate table holds arbitrary e-mails and IPs; that table then needs clean-up. Every failed login would also be a write on the 0.1-CPU database. |
| CAPTCHA after N failures | Needs a third-party script and site key (reCAPTCHA, hCaptcha, Turnstile) in a Flutter web app that loads no third-party scripts today ([0007](0007-authentication-stateless-jwt.md)); does not reduce the BCrypt cost of a burst. |
| Exponential back-off per account (no hard lock) | Harder to explain to a user and to test; a fixed 15-minute lock with a clear message is enough for an internal tool. |
| Limit only per IP | Distributed guessing against one account would not be stopped; a spoofed `X-Forwarded-For` would evade it completely. |
| Limit only per e-mail | One IP could spray one password over many accounts without limit. |
| A servlet filter in front of the controller | The rule needs to know whether the login *failed*, which only `AuthService` knows. |

## Consequences

**Positive**
- Online guessing is limited to 5 tries per account per 15 minutes and 20 failures per IP per 15 minutes.
- A burst against a locked e-mail or a blocked IP costs no BCrypt check.
- No new dependency or service; configurable thresholds; fully testable with the injected `Clock`.
- The 429 has a clear message and a standard `Retry-After` header.

**Negative / accepted trade-offs**
- **State is per instance and lost on restart.** A restart (or a Render redeploy / spin-down) unlocks everything.
  With more than one instance each one would count separately. Accepted: the Render free tier runs one instance.
- **Lockout as denial of service:** anyone who knows an e-mail can keep that account locked by sending 5 wrong
  passwords every 15 minutes. Accepted for an internal tool; the lock is short and the ADMIN can still log in from
  another e-mail.
- **Shared IPs:** an office behind one NAT address shares the 20-failure IP budget; many typos across the office
  could block everyone for 15 minutes.
- **The memory bound can be abused:** an attacker spraying many unknown e-mails from many IPs can fill the 10,000
  slots and evict older entries (including a lock). The per-IP limit makes this expensive from one address.
  *(Corrected, see Amendment: a lock is never evicted any more.)*
- ~~`X-Forwarded-For` spoofing evades the per-IP limit (not the per-e-mail limit), as stated above.~~ Closed by
  the Amendment: client-added entries no longer choose the IP key.
- The attempt that reaches the threshold still gets 401, so the user only learns about the lock on the next try.

**Follow-ups**
- A shared store (Redis or a database table) if the backend ever runs several instances.
- ~~Trust only the right-most proxy-added `X-Forwarded-For` entry~~ — done (Amendment, `native`).
- Optionally notify the account owner (e-mail) on a lock.
- ~~`PUT /auth/me/password` with a wrong current password is not throttled~~ — done (Amendment, SEC-7).
- Update [0015](0015-non-functional-requirements-strategy.md) (NFR-SEC-01) and [0007](0007-authentication-stateless-jwt.md)'s
  follow-up list after implementation.

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
| SEC-7 / F3 | `PUT /auth/me/password` checked `currentPassword` without any limit, so a stolen token allowed unlimited guessing of the account password (and then a takeover by setting a new one). | Wrong current passwords are counted under the user's normalized e-mail (`tryAcquire(email, null)`, no IP key). While the e-mail is locked — by failed logins or by wrong current passwords — the change answers the same **429** before the password is checked; a correct current password clears the count. Still 400 (not 401) for a single wrong current password, as D3 requires. Tests: `AuthServiceTest`, `UserApiTest.wrongCurrentPasswordsAreThrottledLikeFailedLogins`, frontend `app_shell_v21_test.dart` (429 shown in the dialog, session kept). |

**What still holds (residual risk):** a locked e-mail can no longer be freed at all. An e-mail that is close to its
limit (but not locked) can only be pushed out by filling all 10,000 slots with entries at least as close to the
limit, i.e. up to 40,000 failed logins in 15 minutes, which needs about 2,000 real source addresses at 20 failures
each; the per-IP limit counts real source addresses as Render's proxy sees them. If a CDN or a second proxy with a public
address ever sits in front of Render's proxy, its address would be the right-most entry and all its clients would
share one IP budget; then add it with `SERVER_TOMCAT_REMOTEIP_TRUSTED_PROXIES`. The lockout-as-denial-of-service
trade-off for a known e-mail (Consequences) is unchanged.

## Related

- Guideline: sections 4, 13, 17.2 (Security, Errors, Tests); FR-AUTH-01.
- NFR findings: **NFR-SEC-01** (no login rate limit, closed by this ADR), NFR-MAINT-03 (failed logins logged);
  NFR-REL-01 is handled by [0023](0023-optimistic-locking.md).
- ADRs: [0007](0007-authentication-stateless-jwt.md), [0008](0008-error-format-problem-details.md),
  [0011](0011-time-and-expiry-with-injected-clock.md), [0015](0015-non-functional-requirements-strategy.md),
  [0016](0016-deployment-render-and-netlify.md), [0024](0024-admin-user-management.md).
- Files (planned): `BE/auth/LoginAttemptService.java`, `BE/auth/LoginThrottleProperties.java`,
  `BE/auth/AuthService.java`, `BE/auth/AuthController.java`, `BE/common/exception/ApiErrorType.java`,
  `BE/common/exception/GlobalExceptionHandler.java`, `BE/config/CorsConfig.java`,
  `stark-vms-backend/src/main/resources/application.yml`, `FE/features/auth/presentation/login_controller.dart`,
  `FE/core/network/api_exception.dart`, `docs/API_CONTRACT.md` §3 and §7.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
