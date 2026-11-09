# 0017. User account management: accounts from start-up configuration only (known gap)

- **Status:** Superseded by [0024](0024-admin-user-management.md) (was: Accepted as a scope limit for v1.0 and v2.0)
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 built FR-AUTH-01 with
  `AdminBootstrap`, and was kept unchanged in v2.0)

> **Superseded on 2026-11-07 by [0024](0024-admin-user-management.md)** (ADMIN user management, own password
> change, tokens checked against the current user on every request). 0024 follows the rules listed under
> "Follow-ups" below. The text of this ADR is kept unchanged as the record of the v1.0 and v2.0 scope; until 0024 is
> implemented and merged, the code still behaves as described here. Assignment 1 (v1.0) keeps this scope limit
> (its ADR 0007).

## Context

The guideline is not consistent on this point:
- **Section 4** (Users and Roles) gives ADMIN "Everything: … change vehicle status, manage user accounts", and says
  STAFF "Cannot delete records or manage users".
- **Section 11.3** (endpoint catalogue, "this is the contract between your frontend and backend") lists no user
  endpoints. The only auth endpoints are `POST /auth/login` and `GET /auth/me`.
- **Section 5** has no functional requirement for user management (FR-AUTH-01/02 cover login and logout only).
- **Section 13** asks for the "First ADMIN account created from environment variables at start-up, not hard-coded
  in a migration".

Both assignments were built against the endpoint catalogue and the FRs. No requirement ID, acceptance criterion or
test case (TC-01…TC-12) covers creating, editing or disabling users.

## Decision

For v1.0 and v2.0, **user accounts are created only from environment variables at start-up**, and there is no user
management in the API or the UI.

- `BE/auth/AdminBootstrap.java` (`ApplicationRunner`, highest precedence, `@Transactional`):
  - creates the ADMIN from `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` (`APP_ADMIN_FULL_NAME`, default "Fleet Admin");
  - optionally creates one STAFF account from `APP_STAFF_EMAIL` / `APP_STAFF_PASSWORD` (`APP_STAFF_FULL_NAME`,
    default "Fleet Officer");
  - only if that e-mail does not exist yet. "Existing accounts are never modified", so changing
    `APP_ADMIN_PASSWORD` later has no effect on an existing account;
  - a password shorter than 8 characters fails start-up; an empty `APP_ADMIN_EMAIL` only logs a warning.
- The `users` table (`MIG/V1__create_users.sql`) already has `role` (`CHECK ADMIN|STAFF`) and `enabled`.
  - Login rejects a disabled user with the generic 401, and `GET /auth/me` rejects it.
  - `JwtAuthenticationFilter` does not reload the user on each request, so an already-issued token of a disabled
    user stays valid until it expires (at most 60 minutes).
- Any other change (another STAFF user, a password change, disabling a user) is a manual SQL change in the database
  (with a BCrypt hash).
- The frontend has no Users screen. `currentUserProvider` only reads the signed-in user's role to hide buttons.
- The gap is stated in `stark-vms-backend/README.md` ("Known limitations / next steps": "No user management
  endpoints yet").

**This is a known gap against guideline section 4, accepted as a scope limit for now. It is not a design choice
to keep.**

## Alternatives considered

| Alternative | Why rejected (for now) |
|---|---|
| Build ADMIN-only user management in v2.0 | Not in the endpoint catalogue, the FRs or the test cases that v2.0 was planned and tested against; the time went to the ten v2.0 Must features. Kept as the first follow-up. |
| Self-registration | Wrong for an internal fleet tool; anyone could create an account. |
| Seed users in a Flyway migration | Forbidden by guideline 13 (credentials in Git, the same password everywhere). |
| External identity provider (Supabase Auth, Keycloak) | An extra service for two roles; see [0007](0007-authentication-stateless-jwt.md). |

## Consequences

**Positive**
- No credentials in code or migrations. The first ADMIN exists on the first start of a fresh database.
- The attack surface is small: no endpoint can create or elevate an account.

**Negative / accepted trade-offs**
- An ADMIN cannot "manage user accounts" as section 4 says. At most two accounts can be created without touching
  the database.
- There is no password change or reset; a leaked password must be replaced with SQL.
- Disabling a user does not end its current session until the token expires.
- With several instances, the bootstrap check-then-insert can race (NFR-SCAL-02, see
  [0015](0015-non-functional-requirements-strategy.md)).

**Follow-ups** (a new ADR before implementation, superseding this one)
- ADMIN-only endpoints under `/api/v1/users`, all with `@PreAuthorize("hasRole('ADMIN')")`:
  - `GET /users` (paged), `POST /users`;
  - `PUT /users/{id}` (name, role, enabled);
  - `POST /users/{id}/password` (reset).
  The API contract would be extended additively (Problem Details, `PageResponse`, 409 for a duplicate e-mail on
  `uk_users_email`).
- Rules for that ADR:
  - the last enabled ADMIN cannot be disabled or demoted;
  - users are never hard-deleted (audit `created_by` refers to their e-mail), only disabled;
  - a password change by the user themself, with the current password.
- A Users screen (ADMIN only) in a new `features/users/` frontend feature.
- Reject tokens of disabled users: load the user in `JwtAuthenticationFilter`, or keep a token version per user.
- Login rate limiting (NFR-SEC-01) at the same time, because user management adds more accounts to guess.

## Related

- Guideline: sections 4, 5.1 (FR-AUTH-01, FR-AUTH-02), 11.3, 13.
- ADRs: [0007](0007-authentication-stateless-jwt.md), [0015](0015-non-functional-requirements-strategy.md),
  [0018](0018-accepted-deviations-from-the-guideline.md).
- Files: `BE/auth/AdminBootstrap.java`, `BE/auth/BootstrapProperties.java`, `BE/auth/AuthService.java`,
  `BE/auth/JwtAuthenticationFilter.java`, `MIG/V1__create_users.sql`, `stark-vms-backend/src/main/resources/application.yml`
  (`app.bootstrap.*`), `stark-vms-backend/render.yaml`, `docs/API_CONTRACT.md` §1, §5.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`.
