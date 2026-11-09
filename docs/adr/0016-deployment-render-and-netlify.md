# 0016. Deployment: Render (Docker) and Netlify, configuration only through environment variables

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; v2.0 added
  the storage variables to `render.yaml`, and on 2026-11-07 the security headers, the demo-hint default and the
  storage key prefix were added)

## Context

Guideline section 18 fixes the targets: the backend and its PostgreSQL on Render, the Flutter Web build on Netlify,
CI on GitHub Actions, all on free tiers. The portability NFR says "All configuration through environment
variables; the same build runs locally and in production". Section 13 says no secrets in Git. Section 14.2 says
the API base URL comes from `--dart-define=API_BASE_URL=...` and is never hard-coded. Render gives a `postgres://`
URL, while Spring needs `jdbc:postgresql://` (18.3). Deep links such as `/vehicles/12` must survive a browser
refresh on Netlify (18.4).

## Decision

### Backend on Render, as a Docker image

- **`stark-vms-backend/Dockerfile`**, multi-stage:
  - build stage `maven:3.9.9-eclipse-temurin-21`: `dependency:go-offline`, then `package -DskipTests`;
  - run stage `eclipse-temurin:21-jre`, as the non-root user `vms` (uid 10001);
  - `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`.
- **`stark-vms-backend/render.yaml`** (Render Blueprint):
  - a Docker web service `stark-vms-backend` and a PostgreSQL `stark-vms-db`, both on the free plan in
    `singapore`;
  - health check `/actuator/health`, `autoDeploy: true`.
- **Configuration only from the environment** (`application.yml` holds placeholders, no secrets):
  - `PORT`;
  - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`;
  - `JWT_SECRET`, `JWT_EXPIRATION_MINUTES`;
  - `CORS_ALLOWED_ORIGINS`;
  - `APP_ADMIN_*`, `APP_STAFF_*`;
  - `APP_STORAGE_PROVIDER`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET`, `APP_STORAGE_KEY_PREFIX`
    (`render.yaml` sets `prod/`).
- In the Blueprint:
  - `DB_USERNAME` and `DB_PASSWORD` come `fromDatabase`;
  - `JWT_SECRET` uses `generateValue: true`;
  - every other secret is `sync: false` and is entered in the Render dashboard;
  - `DB_URL` is set by hand in its JDBC form (`stark-vms-backend/README.md`, "Deploy to Render").
- `server.port: ${PORT:8080}` and `forward-headers-strategy: framework` (Render terminates TLS in front of the
  app). *(Changed to `native` on 2026-11-08 after the security review, see [0022](0022-login-throttling.md)
  Amendment: Tomcat's `RemoteIpValve` trusts `X-Forwarded-*` only from internal proxies, takes the right-most
  untrusted `X-Forwarded-For` entry and ignores the client-controlled `Forwarded` header.)*
- Profiles:
  - production runs with no profile;
  - the `local` profile (demo data, localhost CORS patterns) must never be set on Render.
- Flyway migrates on start-up. `AdminBootstrap` creates the configured accounts.

### Frontend on Netlify, as a prebuilt static site

- Built with `flutter build web --release --dart-define=API_BASE_URL=https://<service>.onrender.com/api/v1`.
  - `AppConfig.apiBaseUrl` has no default. A build without it shows `MissingConfigurationApp` instead of calling
    a hard-coded server (`FE/core/config/app_config.dart`, `FE/bootstrap.dart`).
  - `SHOW_DEMO_CREDENTIALS` defaults to `!kReleaseMode`.
- **`stark-vms-frontend/netlify.toml`:**
  - publishes `build/web`;
  - SPA rewrite `/* → /index.html 200`;
  - headers `X-Frame-Options: DENY`, `Content-Security-Policy: frame-ancestors 'none'`,
    `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`.
  - `web/_redirects` and `web/_headers` hold the same rules for a drag-and-drop deploy of `build/web`.
- The app uses path URLs (`usePathUrlStrategy()` in `FE/main.dart`), which is why the rewrite is needed.

### CI (GitHub Actions), no automatic deploy step

- Backend (`stark-vms-backend/.github/workflows/ci.yml`): every push to any branch and every pull request run
  Temurin 21 → `mvn -B verify` and upload the surefire and JaCoCo reports.
- Frontend (`stark-vms-frontend/.github/workflows/ci.yml`):
  - every push to any branch and every pull request run Flutter 3.47.7 → `flutter pub get`,
    `dart format --set-exit-if-changed`, `flutter analyze`, `flutter test`, then
    `flutter build web --release` with `API_BASE_URL` and `SHOW_DEMO_CREDENTIALS` from repository variables;
  - the `build/web` artifact is uploaded.
- Render deploys from the repository itself (`autoDeploy`). Netlify gets the prebuilt `build/web` (drag-and-drop,
  CLI or a build hook).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Render native Java runtime (buildpack) instead of Docker | The guideline gives a Dockerfile (18.3); the image also runs locally (`docker run`) exactly as on Render. |
| Fly.io, Railway, Heroku, a VPS | Not the course targets; free tiers and terms differ. |
| Serve the Flutter build from Spring Boot (one deployable) | Couples frontend and backend releases and makes the sleeping Render instance also delay the static UI. Netlify serves the UI from a CDN even while the API sleeps, so the "Connecting to server…" message can be shown. |
| Build Flutter on Netlify | Netlify's build environment would have to install the Flutter SDK on every build. The build is already reproducible in CI with the pinned Flutter version. |
| `application-prod.yml` with production values in Git | Secrets in Git (guideline 13). Everything environment-specific is a variable instead. |
| Hash URLs (`/#/vehicles/12`) to avoid the SPA rewrite | Uglier URLs; the rewrite is one line. |
| Deploy from CI (Render deploy hook, Netlify CLI with a token) | Needs deploy secrets in GitHub. Kept as a follow-up together with branch protection. |

## Consequences

**Positive**
- The same image and the same web build run locally and in production; only variables differ.
- No secret is in either repository. `JWT_SECRET` is generated by Render.
- A misconfigured frontend build fails visibly, not silently.

**Negative / accepted trade-offs**
- Free Render sleeps (cold start up to about a minute) and has one instance ([0015](0015-non-functional-requirements-strategy.md)).
- `DB_URL` must be converted by hand from Render's `postgres://` string.
- `API_BASE_URL` is compiled in. A new backend URL needs a new frontend build.
- `render.yaml` does not pin the PostgreSQL major version ([0006](0006-postgresql-everywhere-flyway-and-validate.md)).
- CI does not deploy. Merging and deploying are separate manual steps, and branch protection ("merging requires a
  green build", 18.2) is a repository setting that is not recorded in the code.
- There is no full CSP yet ([0007](0007-authentication-stateless-jwt.md)).

**Follow-ups**
- Protect `main` in both repositories (required CI checks).
- Optionally deploy from CI with a Render deploy hook and the Netlify CLI.
- Pin the database version in `render.yaml`.

## Related

- Guideline: sections 6 (Portability), 13, 14.2, 18.1–18.4, 21 (deployment risks).
- ADRs: [0006](0006-postgresql-everywhere-flyway-and-validate.md), [0007](0007-authentication-stateless-jwt.md),
  [0013](0013-document-file-storage-behind-storageservice.md), [0015](0015-non-functional-requirements-strategy.md).
- Files: `stark-vms-backend/Dockerfile`, `stark-vms-backend/render.yaml`,
  `stark-vms-backend/src/main/resources/application.yml`, `stark-vms-backend/.github/workflows/ci.yml`,
  `stark-vms-frontend/netlify.toml`, `stark-vms-frontend/web/_redirects`, `stark-vms-frontend/web/_headers`,
  `stark-vms-frontend/.github/workflows/ci.yml`, `FE/core/config/app_config.dart`, `FE/bootstrap.dart`, `FE/main.dart`,
  `stark-vms-backend/README.md` (Configuration, Deploy to Render), `stark-vms-frontend/README.md` (Build and deploy).

Path prefix: `FE/` = `stark-vms-frontend/lib/`.
