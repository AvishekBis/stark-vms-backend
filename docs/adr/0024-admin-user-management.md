# 0024. ADMIN user management, own password change, and tokens checked against the current user

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** before implementation (decision D3 of 2026-11-07; the code does not exist yet; this ADR gets file
  and test references when the change is merged)
- **Implemented:** 2026-11-08 (v2.1). **Amended:** 2026-11-08 after a security review — see "Amendment after the
  security review" below (millisecond token cut-off, tokens revoked on disable, wrong current passwords throttled)
- **Deciders:** project owner
- **Supersedes:** [0017](0017-user-account-management-scope.md) (accounts from start-up configuration only)

## Context

Guideline section 4 gives ADMIN "manage user accounts" and says STAFF "cannot … manage users". v1.0 and v2.0 create
accounts only from environment variables at start-up (`BE/auth/AdminBootstrap.java`); [0017](0017-user-account-management-scope.md)
recorded this as a known gap and listed the rules a later ADR must follow. Today:
- there is no way to add a third account, change a name or role, disable a user or change a password without SQL;
- `JwtAuthenticationFilter` trusts the token alone (`JwtService.parse` builds the principal from the `role` claim),
  so a disabled or demoted user keeps their old rights until the token expires (up to 60 minutes);
- the `users` table (`MIG/V1__create_users.sql`) already has `role` (`CHECK ADMIN|STAFF`), `enabled`, the audit
  columns including `created_by`, and the unique constraint `uk_users_email`;
- the existing minimum password length is **8** (`AdminBootstrap.MIN_PASSWORD_LENGTH`); BCrypt only uses the first
  **72 bytes** of a password, and the Spring Security version in use refuses longer ones when hashing.

Audit columns of every table (`created_by`, `assigned_by`) store the e-mail of the user who made the change, so a
user who has acted must stay identifiable.

## Decision

**ADMINs manage accounts through new `/api/v1/users` endpoints, every user can change their own password, users are
disabled instead of deleted, and every request re-checks the token against the current `users` row so that disabling,
demoting or a password reset takes effect on the next request.**

### Endpoints (base `/api/v1`)

| Method | Path | Role | Result |
|---|---|---|---|
| GET | `/users?search=&role=&enabled=&page=&size=&sort=` | ADMIN | 200 `PageResponse<UserResponse>` |
| GET | `/users/{id}` | ADMIN | 200 `UserResponse` · 404 |
| POST | `/users` | ADMIN | 201 + `Location: /api/v1/users/{id}`, body `UserResponse` |
| PUT | `/users/{id}` | ADMIN | 200 `UserResponse` |
| POST | `/users/{id}/password` | ADMIN | 204 (admin reset) |
| PUT | `/auth/me/password` | any role | 204 (change own password) |

- ADMIN-only endpoints use `@PreAuthorize("hasRole('ADMIN')")`; STAFF gets 403 as everywhere ([0007](0007-authentication-stateless-jwt.md)).
- **List:** `search` is a partial, case-insensitive match on e-mail **or** full name (LIKE input escaped as in the other
  lists); `role` (`ADMIN`|`STAFF`) and `enabled` (`true`|`false`) are optional filters, AND-ed; sort whitelist
  `email`, `fullName`, `createdAt`, default `email,asc`, unknown field → 400; size capped at 100
  ([0010](0010-server-side-pagination-filtering-sorting.md)).
- **Create** `{email, fullName, role, password}`: e-mail trimmed and lower-cased (`AuthService.normalizeEmail`),
  required, valid e-mail, max 255; `fullName` required, max 100; `role` required; the new user is enabled.
  A taken e-mail → **409** `.../errors/duplicate-email` with `errors[{field: "email"}]`, checked in the service **and**
  by `uk_users_email` (registered as a `UniqueConstraintRule`, like the vehicle number) so a race gives the same 409.
- **Update** `{fullName, role, enabled, version}`: all required. **The e-mail is immutable** (it is the login name
  and the value stored in audit columns). `version` follows [0023](0023-optimistic-locking.md) (409 `stale-version`).
- **Admin reset** `{newPassword}` → 204. Allowed on any user, including the ADMIN themself (their own session then
  ends on the next request; the UI offers "Change password" for one's own account instead).
- **Own password change** `{currentPassword, newPassword}` → 204, any role. A wrong current password is **400** with a
  field error on `currentPassword` ("Current password is incorrect."), **not 401**, so the client does not treat it as
  an expired session and log the user out.
- **Password policy** (one `PasswordPolicy` used by create, reset and own change): at least **8 characters** (the
  existing rule) and at most **72 bytes in UTF-8** (the BCrypt limit) → otherwise 400 field error on `password` /
  `newPassword`.
- **No `DELETE /users/{id}`.** Users are disabled (`enabled=false`), because audit columns refer to them and a
  deleted e-mail could be re-created by someone else and inherit the history.

### Guards (409, no `errors[]`)
- **`.../errors/self-modification`** — an ADMIN cannot disable themself (`enabled=false` on their own id) or remove
  their own ADMIN role. Detail: "You cannot disable your own account or remove your own ADMIN role."
- **`.../errors/last-admin`** — the last enabled ADMIN can never be disabled or demoted. Detail: "At least one
  enabled ADMIN must remain."
- **Race protection:** before a change that disables or demotes an ADMIN, `UserService` locks the enabled ADMIN rows
  (`SELECT … FROM users WHERE role = 'ADMIN' AND enabled FOR UPDATE`, `PESSIMISTIC_WRITE`) and counts them in the
  same transaction. Two ADMINs disabling each other at the same moment are serialised: the second transaction waits,
  PostgreSQL re-checks the `WHERE` for the locked rows, and the second change sees only one enabled ADMIN and gets
  `last-admin`. (In the normal flow the caller is an enabled ADMIN, so `self-modification` fires first; `last-admin`
  is the guard for concurrent changes and any future path.)

### `UserResponse` (admin view)
```json
{ "id": 7, "email": "officer2@stark.local", "fullName": "Second Officer", "role": "STAFF", "enabled": true,
  "createdAt": "2026-11-07T08:00:00Z", "updatedAt": "2026-11-07T08:00:00Z", "createdBy": "admin@stark.local",
  "version": 0 }
```
Never the password hash and never `passwordChangedAt`. In code it is `BE/auth/dto/UserAccountResponse.java`, because
`BE/auth/dto/UserResponse.java` already names the 4-field `user` object of `POST /auth/login` and `GET /auth/me`,
which stay unchanged.

### Database (`MIG/V7__add_version_and_user_management.sql`, shared with 0023)
- `users.version BIGINT NOT NULL DEFAULT 0` (0023).
- `users.password_changed_at TIMESTAMPTZ NULL` — `NULL` means "never changed since the account was created".
- `created_by` already exists since V1, so V7 does not add it. Accounts created through `POST /users` get the admin's
  e-mail there through JPA auditing; bootstrap accounts get `"system"` (the auditor when no user is logged in; corrected during implementation, it said `NULL` before).

### Token effects
- `JwtService.parse` returns the verified claims (user id, e-mail, `iat`); it no longer decides the role.
- `JwtAuthenticationFilter` then loads the user **by primary key on every authenticated request** (one indexed
  lookup) and rejects the token with **401** (reason stored for `RestAuthenticationEntryPoint`, as today) when:
  - the user no longer exists, or
  - the user is disabled, or
  - `iat` is before `password_changed_at`. JWT `iat` has whole-second precision, so the comparison is
    `iat < password_changed_at` truncated to seconds: a token issued later in the same second as the change stays
    valid, which is what the client's automatic re-login needs. *(Corrected, see Amendment: compared in
    milliseconds through a new `iat_ms` claim; disabling also sets a cut-off.)*
- The principal's authority is taken from the **current database role**, not from the token's `role` claim (the
  claim stays in the token for the client's information). Disabling, demoting or resetting a password therefore takes
  effect on the next request, without a token blacklist.
- Login of a disabled user stays the generic 401 ([0007](0007-authentication-stateless-jwt.md), [0022](0022-login-throttling.md)).
- Admin reset and own change both set `password_changed_at = now(clock)` and bump `version`.

### Own password change on the client
After `PUT /auth/me/password` returns 204 the old token is no longer valid. **The client re-authenticates
automatically:** it immediately calls `POST /auth/login` with the user's e-mail and the new password, stores the new
session and shows "Password changed." If that login fails (network error, 429), the client clears the session and
opens the login screen with "Your password was changed. Please log in with the new password." This is the simplest
correct option: no new token-issuing endpoint, and the login path is already excluded from the 401 handling in
`AuthInterceptor`.

### Frontend (Assignment 2)
- New feature `FE/features/users/` (data, presentation), same structure as the other features.
- "Users" navigation item in `FE/shared/widgets/app_shell.dart`, visible only to ADMIN.
- Routes `/users`, `/users/new`, `/users/:id/edit`; the router guard sends STAFF to the dashboard (the backend 403 is
  the real control, guideline section 4).
- List: debounced search, role and enabled filters, sort menu, pagination, the three breakpoints (<600 cards,
  600–1024 compact table, >1024 full table), loading / data / empty / error states. `enabled` is shown as a badge with
  text **and** colour ("Enabled" / "Disabled"), colours from the central theme.
- Create / edit form: e-mail read-only on edit; password field only on create; client validation mirrors the server
  rules; 409 `duplicate-email` on the e-mail field; `self-modification` / `last-admin` shown as a form error; 409
  `stale-version` handled as in 0023.
- Disabling a user (switching `enabled` off and saving, or a row action) needs a confirmation dialog
  (`shared/widgets/confirm_dialog.dart`).
- "Reset password" dialog (new password + confirm) on the edit screen and as a row action; hidden on the ADMIN's own
  row.
- "Change password" in the user menu for every role (current, new, confirm), with the re-login flow above.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Hard delete of users | Audit columns (`created_by`, `assigned_by`) hold the e-mail; a deleted e-mail could be re-registered and appear as the author of old changes. A foreign key from every audit column would be a large schema change. Disabling keeps the history true. |
| Token blacklist / revocation list | Needs storage for revoked token ids until they expire (lost on restart if in memory, an extra table otherwise) and still needs a lookup per request. Loading the user by primary key gives the same effect and also catches demotion. |
| Roles in the token only (today's behaviour) | A demoted or disabled user keeps ADMIN rights for up to 60 minutes. Not acceptable once ADMINs can change roles. |
| A per-user `token_version` column instead of `password_changed_at` | Equivalent for revocation, but needs a new claim and a bump in more places; `password_changed_at` also documents *when* the password changed. Disabled and demoted users are covered by the row lookup anyway. |
| Own password change returns a new token | Adds a second token-issuing path that must stay in sync with login (throttling, response shape). Re-login with the new password reuses the existing flow. |
| Own password change via `POST /users/{id}/password` | That endpoint is ADMIN-only and does not ask for the current password; STAFF need a self-service path that proves knowledge of the current password. |
| Wrong current password → 401 | The client treats 401 as "session expired" and logs the user out; a typo must not do that. |
| E-mail editable | It is the login name and the value stored in audit columns; changing it would orphan the history. A new account plus disabling the old one covers the rare case. |
| Self-registration, external identity provider | Rejected in 0017 and 0007; unchanged. |
| Stronger password rules (character classes, breached-password check) | Not required by the guideline; the existing 8-character rule stays, plus the 72-byte BCrypt bound. Can be tightened later in `PasswordPolicy`. |

## Consequences

**Positive**
- Guideline section 4 is met: ADMINs manage accounts, STAFF cannot.
- Disabling, demoting or resetting a password takes effect on the next request instead of after up to 60 minutes.
- Nobody can lock the organisation out: there is always an enabled ADMIN, also under concurrent changes.
- History stays attributable because users are never deleted.
- Users can change a leaked password themselves.

**Negative / accepted trade-offs**
- **One extra primary-key query per authenticated request.** Small compared with the measured p95 (~121 ms, see
  `docs/PERFORMANCE.md`), but the system is no longer purely stateless at the token level; the measurement must be
  repeated after implementation. *(Repeated on 2026-11-08: every MockMvc p95 between 25.5 and 90.7 ms, no
  measurable change; `docs/PERFORMANCE.md`, "Re-check after v2.1".)*
- The token's `role` claim can now disagree with the effective role; only the database role counts.
- ~~A token issued in the same second as a password change (before it) stays valid (sub-second window).~~ Closed by
  the Amendment (SEC-3): only a token from the very same millisecond survives.
- An ADMIN resetting their own password through the admin endpoint is logged out on the next request.
- More attack surface: new endpoints can create and elevate accounts (all ADMIN-only and tested for STAFF → 403).
  Login throttling ([0022](0022-login-throttling.md)) is in place for the extra accounts; since the Amendment wrong
  current passwords on `PUT /auth/me/password` are throttled too.
- `AdminBootstrap` is unchanged: it still creates the first ADMIN only when the e-mail is missing, and its check-then-
  insert race (NFR-SCAL-02) remains.

**Follow-ups**
- ~~Throttle wrong `currentPassword` attempts per user.~~ Done (Amendment, SEC-7).
- Force a password change on first login for accounts created by an ADMIN (`password_changed_at IS NULL`).
- Audit log of user-management actions (who disabled whom, when).
- Update [0015](0015-non-functional-requirements-strategy.md), `docs/ER_DIAGRAM.md` (V7), `docs/PERFORMANCE.md` and the
  READMEs after implementation.

## Amendment after the security review (2026-11-08)

The decision (D3) is unchanged; three mechanisms were corrected.

| Finding | Problem | Correction |
|---|---|---|
| SEC-3 | `password_changed_at` was truncated to whole seconds before it was compared with `iat`, so every token issued earlier in the same second as a reset stayed valid for 60 minutes. An attacker logging in every 200 ms always held such a token, so a reset did not end their access. | `JwtService` adds the claim **`iat_ms`** (issue time in epoch milliseconds); `TokenAuthenticator.issuedBefore` compares it with the cut-off truncated to milliseconds. Only a token from the very same millisecond survives; the client's automatic re-login is a later request (and a BCrypt check) and is never affected. A token without `iat_ms` (older build) falls back to `iat`. Tests: `JwtServiceTest`, `TokenAuthenticatorTest.tokenIssuedEarlierInTheSameSecondAsTheChangeIsRejected`. |
| SEC-5 | Disabling blocked a user's tokens only while the account stayed disabled; enabling it again brought back every unexpired token issued before the disable. | Migration **`V8__add_users_tokens_revoked_at.sql`** adds `users.tokens_revoked_at TIMESTAMPTZ NULL`. `User.updateAccount(..., now)` sets it when an enabled account is disabled (time from the `Clock`); `TokenAuthenticator` rejects tokens issued before it, like before `password_changed_at`. `password_changed_at` keeps its meaning (password changes only). Tests: `UserServiceTest`, `TokenAuthenticatorTest`, `UserApiTest.reEnablingAUserDoesNotRestoreTokensIssuedBeforeTheDisable`. |
| SEC-7 / F3 | Wrong current passwords on `PUT /auth/me/password` were not throttled: a stolen token allowed unlimited guessing, then a takeover by setting a new password. | Counted as failed logins of the user's e-mail ([0022](0022-login-throttling.md), Amendment). While the e-mail is locked the change answers **429** `too-many-login-attempts` (never 401, so the client keeps the session) before the current password is checked. |

## Related

- Guideline: sections 4 (ADMIN manages user accounts), 13 (BCrypt, passwords never returned, first ADMIN from
  environment variables), 14 (screens, states, breakpoints, confirmations), 17.2 (Security, Validation, Errors, Data,
  Tests); FR-AUTH-01, FR-AUTH-02.
- NFR findings: NFR-SEC-01 (handled by [0022](0022-login-throttling.md); this ADR adds accounts and session
  revocation), NFR-REL-01 (version on users, [0023](0023-optimistic-locking.md)), NFR-SCAL-02 (unchanged).
- ADRs: supersedes [0017](0017-user-account-management-scope.md); [0003](0003-data-ownership-and-cross-context-contracts.md)
  (`auth` keeps owning `users`), [0007](0007-authentication-stateless-jwt.md), [0008](0008-error-format-problem-details.md),
  [0010](0010-server-side-pagination-filtering-sorting.md), [0011](0011-time-and-expiry-with-injected-clock.md),
  [0015](0015-non-functional-requirements-strategy.md), [0022](0022-login-throttling.md), [0023](0023-optimistic-locking.md).
- Files (planned): `MIG/V7__add_version_and_user_management.sql`, `BE/auth/User.java`, `BE/auth/UserRepository.java`,
  `BE/auth/UserController.java`, `BE/auth/UserService.java`, `BE/auth/PasswordPolicy.java`, `BE/auth/AuthController.java`,
  `BE/auth/AuthService.java`, `BE/auth/JwtService.java`, `BE/auth/JwtAuthenticationFilter.java`,
  `BE/auth/dto/UserAccountResponse.java`, `BE/auth/dto/CreateUserRequest.java`, `BE/auth/dto/UpdateUserRequest.java`,
  `BE/auth/dto/ResetPasswordRequest.java`, `BE/auth/dto/ChangePasswordRequest.java`,
  `BE/common/exception/ApiErrorType.java`, `BE/config/SecurityConfig.java`, `FE/features/users/`,
  `FE/features/auth/`, `FE/core/router/app_router.dart`, `FE/shared/widgets/app_shell.dart`,
  `docs/API_CONTRACT.md` §7.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
