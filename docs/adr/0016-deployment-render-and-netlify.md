# 0016. Deployment: Render (Docker) and Netlify, configuration only through environment variables

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; the
  Netlify security headers and the demo-hint default were added on 2026-11-07)

## Context

Guideline section 18 fixes the targets, all on free tiers:
- the backend and its PostgreSQL on Render;
- the Flutter Web build on Netlify;
- CI on GitHub Actions.

Other requirements that shape the deployment:
- **Portability NFR:** "All configuration through environment variables; the same build runs locally and in
  production".
- **Section 13:** no secrets in Git.
- **Section 14.2:** the API base URL comes from `--dart-define=API_BASE_URL=...` and is never hard-coded.
- **18.3:** Render gives a `postgres://` URL, but Spring needs `jdbc:postgresql://`.
- **18.4:** deep links such as `/vehicles/12` must survive a browser refresh on Netlify.

## Decision

### Backend on Render, as a Docker image

- **`stark-vms-backend/Dockerfile`** is a multi-stage build:
  - build stage: `maven:3.9.9-eclipse-temurin-21` runs `dependency:go-offline`, then `package -DskipTests`;
  - run stage: `eclipse-temurin:21-jre`, as the non-root user `vms` (uid 10001), with
    `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`.
- **`stark-vms-backend/render.yaml`** is a Render Blueprint:
  - a Docker web service `stark-vms-backend` and a PostgreSQL `stark-vms-db`, both on the free plan in `singapore`;
  - health check `/actuator/health` and `autoDeploy: true`.
- **Configuration comes only from the environment.** `application.yml` holds placeholders, never secrets. The
  variables are:
  - `PORT`;
  - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`;
  - `JWT_SECRET`, `JWT_EXPIRATION_MINUTES`;
  - `CORS_ALLOWED_ORIGINS`;
  - `APP_ADMIN_*`, `APP_STAFF_*`.
- **How the Blueprint fills them:**
  - `DB_USERNAME` and `DB_PASSWORD` come `fromDatabase`;
  - `JWT_SECRET` uses `generateValue: true`;
  - the other secrets are `sync: false`;
  - `DB_URL` is set by hand in its JDBC form (`stark-vms-backend/README.md`, "Deploy to Render").
- **Server settings:** `server.port: ${PORT:8080}` and `forward-headers-strategy: framework`. *(Changed to `native`
  on 2026-11-08 after the security review, see [0022](0022-login-throttling.md) Amendment: Tomcat's `RemoteIpValve`
  trusts `X-Forwarded-*` only from internal proxies and ignores the client-controlled `Forwarded` header.)*
- **Profiles:** production runs with no profile, and the `local` profile must never be set on Render.
- **Start-up:** Flyway migrates the schema, then `AdminBootstrap` creates the configured accounts.

### Frontend on Netlify, as a prebuilt static site

- **Build:** `flutter build web --release --dart-define=API_BASE_URL=https://<service>.onrender.com/api/v1`.
  - `AppConfig.apiBaseUrl` has no default. A build without it shows `MissingConfigurationApp`
    (`FE/core/config/app_config.dart`, `FE/bootstrap.dart`).
  - `SHOW_DEMO_CREDENTIALS` defaults to `!kReleaseMode`.
- **`stark-vms-frontend/netlify.toml`:**
  - publishes `build/web`, with the SPA rewrite `/* → /index.html 200`;
  - sends the headers `X-Frame-Options: DENY`, `Content-Security-Policy: frame-ancestors 'none'`,
    `X-Content-Type-Options: nosniff` and `Referrer-Policy: strict-origin-when-cross-origin`;
  - `web/_redirects` and `web/_headers` hold the same rules for a drag-and-drop deploy.
- **URLs:** the app uses path URLs (`usePathUrlStrategy()` in `FE/main.dart`).

### CI (GitHub Actions), no deploy step

- **Backend** (`stark-vms-backend/.github/workflows/ci.yml`): runs on every push and every pull request.
  - Temurin 21 → `mvn -B verify`;
  - uploads the surefire and JaCoCo reports.
- **Frontend** (`stark-vms-frontend/.github/workflows/ci.yml`): runs on every push to any branch and every pull
  request.
  - Flutter 3.47.7 → `dart format --set-exit-if-changed` → `flutter analyze` → `flutter test` →
    `flutter build web --release`, with `API_BASE_URL` and `SHOW_DEMO_CREDENTIALS` taken from repository variables;
  - uploads `build/web`.
- **Deploys:** Render deploys from the repository (`autoDeploy`). Netlify receives the prebuilt `build/web`.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Render native Java runtime instead of Docker | The guideline gives a Dockerfile (18.3), and the image also runs locally, the same as on Render. |
| Other hosts (Fly.io, Railway, a VPS) | Not the course targets. |
| Serve the Flutter build from Spring Boot | Couples the frontend and backend releases. It would also mean the sleeping Render instance delays the static UI, whereas Netlify serves the UI even while the API sleeps. |
| Build Flutter on Netlify | The Netlify build would have to install the Flutter SDK on every build. CI already builds with the pinned version. |
| An `application-prod.yml` in Git | It would put secrets in Git (guideline 13). |
| Hash URLs (`/#/vehicles/12`) | Uglier URLs; the rewrite is one line. |

## Consequences

**Positive**
- The same image and the same web build run locally and in production. Only the variables differ.
- No secret is in either repository.
- A misconfigured frontend build fails visibly.

**Negative / accepted trade-offs**
- The free Render instance sleeps: a cold start can take up to about a minute, and there is only one instance
  ([0015](0015-non-functional-requirements-strategy.md)).
- `DB_URL` must be converted by hand, and `API_BASE_URL` is compiled into the build.
- `render.yaml` does not pin the PostgreSQL major version.
- CI does not deploy. Branch protection (18.2) is a repository setting and is not recorded in the code.

**Follow-ups**
- Protect `main` with required CI checks.
- Optionally deploy from CI.
- Pin the database version.

## Deployed (2026-11-09)

Both assignments share one Render service and one Netlify site, because the free tiers allow only a small number
of each and v2.x keeps every v1.0 feature working.

| | URL |
|---|---|
| Frontend (Netlify) | <https://stark-vms-avishek.netlify.app> |
| Backend (Render, Singapore, free) | <https://stark-vms-backend-i2i2.onrender.com> |
| Database | Render PostgreSQL `stark-vms-db` (free) |

What the first deployment taught us, now written into the backend README:

- **Render renames the database.** `databaseName: vms` in `render.yaml` became `vms_vyqp`. `DB_URL` must use the
  dashboard's *Database* value, or start-up fails with `FATAL: database "vms" does not exist`.
- **The first Blueprint deploy always fails.** `DB_URL` is `sync: false` and the database host does not exist
  until Render has created it, so the real URL can only be set on the second attempt.
- **Netlify sites start private.** A new team's site answers 401 (`/edge-access`) to everyone who is not signed
  in to Netlify. *Project configuration → General → Visitor access → Public* is required before review.
- **The site name is part of the contract**: `CORS_ALLOWED_ORIGINS` has to match it exactly, so rename the
  Netlify site before building the frontend against it.
- The live deployment now runs **v2.0**; Assignment 1 as submitted is the `v1.0.0` tag in both repositories.
  The shared database was created by v2.x, so `V2_1__add_vehicle_version.sql` is the same file in both versions
  (see [0023](0023-optimistic-locking.md)).

## Related

- Guideline: sections 6 (Portability), 13, 14.2, 18.1–18.4, 21.
- ADRs: [0006](0006-postgresql-everywhere-flyway-and-validate.md), [0007](0007-authentication-stateless-jwt.md),
  [0015](0015-non-functional-requirements-strategy.md), [0020](0020-test-strategy-and-ci.md).
- Files:
  - backend: `stark-vms-backend/Dockerfile`, `stark-vms-backend/render.yaml`,
    `stark-vms-backend/src/main/resources/application.yml`, `stark-vms-backend/.github/workflows/ci.yml`;
  - frontend: `stark-vms-frontend/netlify.toml`, `stark-vms-frontend/web/_redirects`,
    `stark-vms-frontend/web/_headers`, `stark-vms-frontend/.github/workflows/ci.yml`,
    `FE/core/config/app_config.dart`, `FE/bootstrap.dart`, `FE/main.dart`.

Path prefix: `FE/` = `stark-vms-frontend/lib/`.
