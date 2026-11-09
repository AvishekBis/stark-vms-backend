# 0007. Authentication: stateless JWT, BCrypt and roles with `@PreAuthorize`

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 built FR-AUTH-01/02)

## Context

FR-AUTH-01 and FR-AUTH-02 require:
- login with e-mail and password, and a generic 401 on wrong credentials;
- a token that expires (about 60 minutes);
- logout, and a redirect to login when the token is expired or missing.

Section 4 defines two roles. STAFF cannot delete records, and roles are enforced on the backend with
`@PreAuthorize`.

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
  `/v3/api-docs`, and `/error`. Everything else needs a token (TC-06).
- **Tokens** (`BE/auth/JwtService.java`, jjwt 0.12.6):
  - HS256, with claims `sub` = e-mail, `userId` and `role`;
  - expiry from `JWT_EXPIRATION_MINUTES` (default 60), with timestamps from the injected `Clock`;
  - start-up fails if `JWT_SECRET` is shorter than 32 bytes (256 bits).
- **Passwords:** `BCryptPasswordEncoder`.
  - For unknown e-mails, `AuthService` also runs a BCrypt check against a dummy hash (`timingEqualizerHash`), so
    the response time does not reveal whether an account exists.
  - A wrong password, an unknown e-mail and a disabled user all return the same 401 Problem Details.
- **Roles** (`Role`: ADMIN, STAFF; DB `CHECK ck_users_role`):
  - `@EnableMethodSecurity`, and `@PreAuthorize("hasRole('ADMIN')")` on `DELETE /vehicles/{id}`;
  - STAFF gets 403 Problem Details from `RestAccessDeniedHandler` (TC-07);
  - a missing or invalid token gets 401 from `RestAuthenticationEntryPoint`.
- **Accounts:** `AdminBootstrap` creates the ADMIN from `APP_ADMIN_EMAIL`/`APP_ADMIN_PASSWORD`, and optionally a
  STAFF account from `APP_STAFF_*`.
  - It acts only when the e-mail does not exist yet, and the password must be at least 8 characters.
  - **There is no user management in v1.0.** Guideline section 4 says ADMIN can "manage user accounts", but the
    section 11.3 endpoint catalogue lists no user endpoint. This is a known gap; it is recorded in full in
    Assignment 2's ADR 0017.
- **CORS** (`CorsConfig`, `CorsProperties`):
  - an explicit origin list from `CORS_ALLOWED_ORIGINS`; `*` is rejected (`CorsPropertiesTest`);
  - the `local` profile adds the `http://localhost:*` and `http://127.0.0.1:*` patterns;
  - `Location` is exposed.

**Frontend**
- The session (access token, expiry, user) is stored as JSON under one key through `shared_preferences`, which
  is `localStorage` on the web (`FE/features/auth/data/auth_repository.dart`, `SessionStorage`).
- While the app runs, the token is held in memory (`AccessTokenHolder`) and attached by `AuthInterceptor`.
- A timer signs out at expiry, and any 401 except on login clears the session
  ([0004](0004-frontend-state-management-with-riverpod.md)).
- Logout discards the token on the client. There is no refresh token.

**Accepted trade-off: token in browser storage.**
- Any script on the page can read `localStorage`, so an XSS bug would expose the token.
- It is accepted for the course because:
  - the token lives 60 minutes;
  - the app renders no user-supplied HTML and loads no third-party script;
  - the session is cleared on logout, on expiry and on any 401;
  - Netlify sends `X-Frame-Options: DENY`, `frame-ancestors 'none'` and `nosniff` (`stark-vms-frontend/netlify.toml`).
- Both READMEs describe the trade-off, as section 13 requires.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Server-side sessions (`JSESSIONID`) | Needs sticky sessions or a session store for more than one instance. A cross-site cookie between Netlify and Render needs `SameSite=None` plus CSRF protection. |
| `HttpOnly` refresh cookie + in-memory access token | The better production design. It needs a refresh endpoint, rotation, CSRF protection and cross-site cookie settings, which is too much for v1.0. Kept as a follow-up. |
| Token only in memory | A browser reload would log the user out. |
| OAuth2 / external identity provider | An extra service and account for a two-role internal tool. |
| RS256 | Only one service issues and verifies tokens, so HS256 with a 256-bit secret is enough. |
| Roles checked only in the UI | Explicitly forbidden by guideline section 4. |

## Consequences

**Positive**
- Any instance can verify any token, and nothing is stored on the server.
- The ADMIN-only delete is enforced on the server and tested: STAFF → 403 and ADMIN → 204 (TC-07, `VehicleApiTest`).
- No credentials are in the code or the migrations.

**Negative / accepted trade-offs**
- There is no server-side logout or revocation. A stolen token stays valid until it expires.
- The XSS exposure described above.
- **`POST /auth/login` has no rate limit or lockout.** Online password guessing is unlimited. Every attempt costs a
  BCrypt check, deliberately also for unknown e-mails, so a burst of requests can saturate the small free Render
  instance. *(Closed in v1.1, 2026-11-08, by [0022](0022-login-throttling.md): 429 after 5 failures per e-mail or
  20 per IP; locked attempts run no BCrypt.)*
- `AdminBootstrap` does check-then-insert. Two instances that start at the same time can race on `uk_users_email`,
  and the second one then aborts its start-up.
- No user management (see above).

**Follow-ups**
- A login rate limit and lockout, per account and per IP. *(Done in v1.1, 2026-11-08: [0022](0022-login-throttling.md).)*
- A refresh-token design with an `HttpOnly` cookie, in its own ADR.
- A bootstrap that is safe to run concurrently.
- ADMIN-only user management (`/api/v1/users`) and a Users screen.
- A full Content-Security-Policy.

## Related

- Guideline: sections 4, 13, 14.2, FR-AUTH-01, FR-AUTH-02, TC-06, TC-07.
- ADRs: [0004](0004-frontend-state-management-with-riverpod.md), [0008](0008-error-format-problem-details.md),
  [0015](0015-non-functional-requirements-strategy.md).
- Files: `BE/config/SecurityConfig.java`, `BE/config/CorsConfig.java`, `BE/auth/JwtService.java`,
  `BE/auth/AuthService.java`, `BE/auth/AdminBootstrap.java`, `BE/auth/RestAuthenticationEntryPoint.java`,
  `BE/auth/RestAccessDeniedHandler.java`, `FE/features/auth/data/auth_repository.dart`,
  `FE/core/network/api_client.dart`, `stark-vms-frontend/netlify.toml`, `stark-vms-backend/README.md` and
  `stark-vms-frontend/README.md` (security trade-off sections).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
