# 0007. Authentication: stateless JWT, BCrypt and roles with `@PreAuthorize`

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; v2.0
  added `@PreAuthorize` to the new delete and status endpoints)
- **Updated:** 2026-11-08 — the v2.1 notes "(Closed in v2.1 ...)" below point to [0022](0022-login-throttling.md)
  and [0024](0024-admin-user-management.md); the original decision text is unchanged

## Context

FR-AUTH-01/02 require:
- e-mail and password login;
- a generic 401 on wrong credentials;
- a token that expires (about 60 min);
- logout, and a redirect to login on an expired or missing token.

Section 4 defines two roles. ADMIN may do everything, and STAFF cannot delete or change vehicle status. Roles
must be enforced on the backend with `@PreAuthorize`.

Section 13 asks for:
- BCrypt;
- a JWT secret of at least 256 bits from an environment variable;
- CORS limited to the Netlify site and localhost;
- the first ADMIN created from environment variables at start-up;
- a README note on the browser token-storage trade-off.

The frontend is a static SPA on another origin (Netlify) than the API (Render).

## Decision

**Backend**
- **Stateless JWT** (`BE/config/SecurityConfig.java`):
  - `SessionCreationPolicy.STATELESS`;
  - CSRF, HTTP Basic, form login and logout are disabled;
  - `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`.
- **Public endpoints:** `OPTIONS /**`, `POST /api/v1/auth/login`, `GET /actuator/health/**`, Swagger UI and
  `/v3/api-docs`, and `/error`. Everything else needs authentication.
- **Tokens** (`BE/auth/JwtService.java`, jjwt 0.12.6):
  - HS256 with claims `sub` = e-mail, `userId` and `role`;
  - expiry from `JWT_EXPIRATION_MINUTES` (default 60), with timestamps from the injected `Clock`;
  - start-up fails if `JWT_SECRET` is shorter than 32 bytes (256 bits) or the expiry is ≤ 0.
- **Passwords:** `BCryptPasswordEncoder`. For unknown e-mails, `AuthService` also runs a BCrypt check against a
  dummy hash computed at start-up (`timingEqualizerHash`), so response time does not reveal whether an account exists. Wrong password, unknown e-mail and
  disabled user all return the same 401 Problem Details.
- **Roles** (`Role`: ADMIN, STAFF, DB `CHECK`):
  - `@EnableMethodSecurity` and `@PreAuthorize("hasRole('ADMIN')")` on `DELETE /vehicles/{id}`,
    `PATCH /vehicles/{id}/status`, `DELETE /drivers/{id}`, `DELETE /documents/{id}` and
    `DELETE /maintenance/{id}`;
  - STAFF gets 403 Problem Details from `RestAccessDeniedHandler`, and a missing or invalid token gets 401 from
    `RestAuthenticationEntryPoint`.
- **Accounts:** `AdminBootstrap` (`ApplicationRunner`, highest precedence) creates the ADMIN from
  `APP_ADMIN_EMAIL`/`APP_ADMIN_PASSWORD`, and optionally a STAFF account from `APP_STAFF_*`.
  - It runs only if the e-mail is not taken, with a minimum password length of 8.
  - There are no user-management endpoints; see [0017](0017-user-account-management-scope.md). *(Closed in v2.1,
    2026-11-08, by [0024](0024-admin-user-management.md): ADMIN-only `/users` endpoints and `PUT /auth/me/password`;
    0017 is superseded.)*
- **CORS** (`CorsConfig`, `CorsProperties`):
  - an explicit origin list from `CORS_ALLOWED_ORIGINS`; `*` is rejected (`CorsPropertiesTest`);
  - the `local` profile adds the `http://localhost:*` and `http://127.0.0.1:*` patterns;
  - `Location` and `Content-Disposition` are exposed.

**Frontend**
- The session (access token, expiry, user) is stored as JSON under one key through `shared_preferences`, which
  is `localStorage` on the web (`FE/features/auth/data/auth_repository.dart`, `SessionStorage`).
- While the app runs, the token is held in memory (`AccessTokenHolder`) and attached by `AuthInterceptor`.
- A timer signs out at expiry, and any 401 except on login clears the session ([0004](0004-frontend-state-management-with-riverpod.md)).
- There is no refresh token: the user logs in again after 60 minutes.

**Accepted trade-off: token in browser storage.**
- Any script running on the page can read `localStorage`, so an XSS bug would expose the token.
- This is accepted for the course for these reasons:
  - the token lives 60 minutes;
  - the app renders no user-supplied HTML and loads no third-party script;
  - the session is cleared on logout, expiry and 401;
  - Netlify sends `X-Frame-Options: DENY`, `frame-ancestors 'none'` and `nosniff` (`stark-vms-frontend/netlify.toml`).
- The trade-off is described in both READMEs, as section 13 requires.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Server-side sessions (`JSESSIONID`) | Needs sticky sessions or a session store for more than one instance, and a cross-site cookie between Netlify and Render needs `SameSite=None` plus CSRF protection. The guideline asks for a stateless SPA setup. |
| `HttpOnly` refresh cookie + in-memory access token | The better production design (it removes the XSS exposure of a long-lived credential). It needs a refresh endpoint, refresh-token storage and rotation, CSRF protection for the cookie, and cross-site cookie settings. This is too much for the course scope; recorded as a follow-up. |
| Token only in memory (no storage) | A browser reload would log the user out, which is poor UX for a web app. |
| OAuth2 / external identity provider (Keycloak, Auth0, Supabase Auth) | An extra service and account for a two-role internal tool; the guideline asks for e-mail and password with JWT. |
| RS256 (asymmetric) | Only one service issues and verifies tokens; HS256 with a 256-bit secret is sufficient. |
| Roles checked only in the UI | Explicitly forbidden by guideline section 4. |

## Consequences

**Positive**
- Any instance can verify any token; nothing is stored server-side.
- Roles are enforced in one visible place per endpoint and are covered by tests (STAFF → 403 on every ADMIN
  endpoint, TC-06, TC-07).
- No credentials are hard-coded in migrations or code.

**Negative / accepted trade-offs**
- There is no server-side logout or revocation: a stolen token stays valid until it expires. *(Largely closed in
  v2.1, 2026-11-08, by [0024](0024-admin-user-management.md): the user is loaded on every request, so disabling,
  demoting, a password reset or an own password change revoke older tokens on the next request; a re-enabled
  account does not revive them. There is still no logout endpoint: logging out only clears the client session.)*
- The XSS exposure described above.
- `POST /auth/login` has no rate limit or lockout (finding NFR-SEC-01):
  - online password guessing is unlimited;
  - each attempt costs a BCrypt check (deliberately, also for unknown e-mails), so a burst of requests can
    saturate the 0.1-CPU free Render instance.

  *(Closed in v2.1, 2026-11-08, by [0022](0022-login-throttling.md): 429 after 5 failures per e-mail or 20 per IP
  within 15 minutes, without a password check while locked; hardened after the security review.)*
- `AdminBootstrap` does a check-then-insert. Two instances that start at the same time can both pass
  `existsByEmail`, and the second then fails on `uk_users_email` and aborts start-up (finding NFR-SCAL-02).

**Follow-ups**
- Rate limiting and temporary lockout on login, per account and per IP (for example Bucket4j), with failed
  logins logged with account and IP (see NFR-MAINT-03 in 0015). *(Done in v2.1, 2026-11-08:
  [0022](0022-login-throttling.md), in memory, without Bucket4j.)*
- A refresh-token design with an `HttpOnly` cookie, in its own ADR.
- Make `AdminBootstrap` tolerate a concurrent insert (catch the unique violation, or `INSERT ... ON CONFLICT DO NOTHING`).
- A full Content-Security-Policy (`script-src` with the hash of Flutter's inline loader, `connect-src` with the
  API origin).

## Related

- Guideline: sections 4, 13, 14.2, FR-AUTH-01, FR-AUTH-02, TC-06, TC-07.
- ADRs: [0004](0004-frontend-state-management-with-riverpod.md), [0008](0008-error-format-problem-details.md),
  [0015](0015-non-functional-requirements-strategy.md), [0017](0017-user-account-management-scope.md).
- Files: `BE/config/SecurityConfig.java`, `BE/config/CorsConfig.java`, `BE/auth/JwtService.java`,
  `BE/auth/AuthService.java`, `BE/auth/AdminBootstrap.java`, `BE/auth/RestAuthenticationEntryPoint.java`,
  `BE/auth/RestAccessDeniedHandler.java`, `FE/features/auth/data/auth_repository.dart`,
  `FE/core/network/api_client.dart`, `stark-vms-frontend/netlify.toml`,
  `stark-vms-backend/README.md` and `stark-vms-frontend/README.md` (security trade-off sections).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
