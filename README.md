# Stark Group VMS — Backend

Fleet management REST API for Stark Group. **v1.0 (Assignment 1):** login with JWT, vehicle register
(list / add / edit / delete) and duplicate vehicle number validation, all on a real PostgreSQL database.
**v1.1 hardening:** login throttling (429 after repeated failed logins, [ADR 0022](docs/adr/0022-login-throttling.md))
and optimistic locking on vehicle edits (409 `stale-version`, [ADR 0023](docs/adr/0023-optimistic-locking.md));
contract section 6. v2.0 (documents, maintenance, drivers, dashboard) plugs into the same structure.

## Live

| | URL |
|---|---|
| Frontend | `https://<your-site>.netlify.app` |
| API base | `https://<your-service>.onrender.com/api/v1` |
| API docs (Swagger UI) | `https://<your-service>.onrender.com/swagger-ui.html` |
| Health | `https://<your-service>.onrender.com/actuator/health` |

Demo login (non-real credentials, demo/local only): `admin@stark.local` / `Admin@12345` (ADMIN),
`staff@stark.local` / `Staff@12345` (STAFF).

> Render free instances sleep when idle; the first request can take up to a minute.

## Tech stack (exact versions, pinned in `pom.xml`)

| Area | Choice | Version |
|---|---|---|
| Language / runtime | Java (Eclipse Temurin) | 21 (LTS) |
| Framework | Spring Boot (parent) | 3.5.6 |
| | Spring Framework / Spring Security | 6.2.11 / 6.5.5 |
| Build | Maven (via wrapper `mvnw`) | 3.9.9 |
| Persistence | Spring Data JPA / Hibernate ORM | 6.6.29.Final |
| Database | PostgreSQL (JDBC driver) | 42.7.7 (server 16+/17 recommended) |
| Migrations | Flyway (`flyway-core` + `flyway-database-postgresql`) | 11.7.2 |
| Security | JJWT (`jjwt-api`, `-impl`, `-jackson`), BCrypt | 0.12.6 |
| API docs | springdoc-openapi-starter-webmvc-ui | 2.8.13 |
| JSON | Jackson | 2.19.2 |
| Tests | JUnit Jupiter / Mockito / Spring Security Test | 5.12.2 / 5.17.0 / 6.5.5 |
| Embedded PostgreSQL (tests + local run) | `io.zonky.test:embedded-database-spring-test` / `embedded-postgres` / binaries BOM | 2.6.0 / 2.1.0 / 17.5.0 |
| Coverage | JaCoCo Maven plugin | 0.8.13 |

## Architecture

The reasons for this architecture (bounded contexts, data ownership, layering, state management, security, NFRs) are recorded as Architecture Decision Records in [`../docs/adr/`](docs/adr/README.md).

```mermaid
flowchart LR
    FE["Flutter Web (Netlify)"] -- "HTTPS + JWT<br/>JSON /api/v1" --> SEC
    subgraph API["Spring Boot (Render, Docker)"]
        SEC["Security filter chain<br/>JwtAuthenticationFilter, CORS"] --> C["Controllers<br/>(DTO records, @Valid)"]
        C --> S["Services<br/>(business rules, @Transactional)"]
        S --> R["Spring Data repositories"]
        C -. errors .-> EH["GlobalExceptionHandler<br/>(Problem Details)"]
    end
    R --> DB[("PostgreSQL<br/>Flyway migrations")]
```

The code is organised **by feature** (`auth/`, `vehicle/`, later `driver/`, `document/` …) with shared
infrastructure in `config/` and `common/`. Each request passes Controller → Service → Repository; controllers only
map HTTP, services own every rule and transaction, and JPA entities never leave the service layer (DTOs are Java
records). Every error, including 401/403 from the security filters, leaves the API in one format: RFC 9457
Problem Details.

```
src/main/java/com/starkgroup/vms/
├── VmsApplication.java
├── config/        SecurityConfig, CorsConfig(+Properties), OpenApiConfig, ClockConfig (Asia/Dhaka), JpaAuditingConfig
├── common/
│   ├── dto/         PageResponse (stable page wrapper)
│   ├── exception/   GlobalExceptionHandler, ApiErrorType, UniqueConstraintRule, NotFound/DuplicateResource/BusinessRule/BadRequest…,
│   │                StaleVersionException (409 stale-version), TooManyLoginAttemptsException (429 + Retry-After)
│   ├── persistence/ AuditableEntity (createdAt, updatedAt, createdBy — FR-X01)
│   ├── util/        PageRequests (size cap + sort whitelist), LikePatterns
│   ├── validation/  @ManufactureYear (uses the injected Clock)
│   └── web/         RequestLoggingFilter (method, path, status, duration), ProblemResponseWriter,
│                    ProblemDetailsErrorController (/error as Problem Details)
├── auth/          AuthController, AuthService, JwtService, JwtAuthenticationFilter, User, Role, AdminBootstrap,
│                  LoginAttemptService + LoginThrottleProperties (in-memory login throttling, ADR 0022), …
├── vehicle/       VehicleController, VehicleService, VehicleRepository, Vehicle, VehicleNumber, VehicleMapper,
│                  VehicleSpecifications, VehicleSortField, enums, dto/
└── dev/           DemoDataSeeder (only when app.seed-demo-data=true — local profile)
src/main/resources/db/migration/  V1__create_users.sql, V2__create_vehicles.sql,
                                  V2_1__add_vehicle_version.sql (v1.1: vehicles.version for optimistic locking)
```

## Run locally

### Option A — no PostgreSQL installation needed (embedded PostgreSQL)

```bash
./mvnw spring-boot:test-run          # Windows: mvnw.cmd spring-boot:test-run
```

`src/test/java/.../TestVmsApplication.java` starts a real PostgreSQL 17 server from bundled binaries on port
**5433**, runs the Flyway migrations, activates the `local` profile and seeds 26 demo vehicles plus the two demo
accounts. The API is on <http://localhost:8080>, Swagger UI on <http://localhost:8080/swagger-ui.html>. Data is
temporary and recreated on every start. First start downloads dependencies and takes about a minute.

### Option B — your own PostgreSQL

1. Create a database: `createdb vms` (or `CREATE DATABASE vms;` in psql).
2. Copy `application-local.example.yml` to `application-local.yml` (git-ignored) in the project root and adjust
   the values.
3. `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`

The `local` profile seeds demo data when the `vehicles` table is empty and allows CORS from any
`http://localhost:<port>`, so `flutter run -d chrome` works whatever port Flutter picks.

## Configuration (environment variables)

`application.yml` contains no secrets — only placeholders.

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `PORT` | no | `8080` | HTTP port (Render sets it) |
| `DB_URL` | yes | – | JDBC URL, e.g. `jdbc:postgresql://host:5432/vms` |
| `DB_USERNAME` / `DB_PASSWORD` | yes | – | Database credentials |
| `DB_POOL_SIZE` | no | `5` | Hikari max pool size (free DBs allow few connections) |
| `JWT_SECRET` | yes | – | HS256 key, **at least 32 bytes / 256 bits**; the app refuses to start otherwise |
| `JWT_EXPIRATION_MINUTES` | no | `60` | Access-token lifetime (`expiresIn` in the login response) |
| `CORS_ALLOWED_ORIGINS` | yes in prod | empty | Comma-separated origins, e.g. `https://stark-vms.netlify.app`. Never `*` (the app refuses to start with it) |
| `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` | first start | empty | First ADMIN, created at start-up only if the e-mail does not exist (password ≥ 8 chars) |
| `APP_ADMIN_FULL_NAME` | no | `Fleet Admin` | Display name of that admin |
| `APP_STAFF_EMAIL` / `APP_STAFF_PASSWORD` / `APP_STAFF_FULL_NAME` | no | empty / `Fleet Officer` | Optional STAFF account, same rule |
| `APP_SECURITY_LOGIN_MAX_FAILURES_PER_EMAIL` | no | `5` | Failed logins for one e-mail within the lockout window that lock that e-mail (ADR 0022) |
| `APP_SECURITY_LOGIN_MAX_FAILURES_PER_IP` | no | `20` | Failed logins from one client IP within the window that block that IP |
| `APP_SECURITY_LOGIN_LOCKOUT` | no | `15m` | Lock length, also the window in which failures are counted (Spring duration: `15m`, `900s`). Values ≤ 0 stop the start-up |
| `SPRING_PROFILES_ACTIVE` | no | – | `local` for development only (demo data, localhost CORS) |

Generate a secret with e.g. `openssl rand -base64 48`.

## API summary

Full contract: [`../docs/API_CONTRACT.md`](docs/API_CONTRACT.md) and Swagger UI. Postman collection:
[`docs/postman_collection.json`](docs/postman_collection.json) (login → list → create → duplicate → edit → stale
edit (409) → delete).

| Method | Path | Role | FR |
|---|---|---|---|
| POST | `/api/v1/auth/login` (429 + `Retry-After` when throttled) | public | AUTH-01, ADR 0022 |
| GET | `/api/v1/auth/me` | any | AUTH-02 |
| GET | `/api/v1/vehicles?page=&size=&sort=&search=` (rows carry `version`) | any | V01 |
| GET | `/api/v1/vehicles/{id}` (with `version`) | any | V01 |
| POST | `/api/v1/vehicles` → 201 + `Location` (no `version` in the body) | any | V02, V05 |
| PUT | `/api/v1/vehicles/{id}` — `version` required, 409 `stale-version` | any | V03, V05, ADR 0023 |
| DELETE | `/api/v1/vehicles/{id}` → 204 | ADMIN | V04 |

## Tests

```bash
./mvnw verify
```

Runs 139 tests (all green) and writes a coverage report to `target/site/jacoco/index.html`
(93.5 % line and 79.6 % branch coverage overall; the dev-only demo seeder, which the tests do not start, is uncovered;
98 % in `vehicle`, 97 % in `auth`, 100 % in `VehicleService`, `AuthService` and `LoginAttemptService`). Saved evidence of the last run:
[`../docs/test-reports/backend-summary.md`](docs/test-reports/backend-summary.md). API tests run against a real embedded
PostgreSQL (zonky) with the Flyway schema, so constraints such as `uk_vehicles_number_key` are exercised — no
Docker required, locally or in CI.
The compiler runs with `-Xlint:all` (main and test code) and the build shows no javac warnings.

| Class | Level | Covers |
|---|---|---|
| `VehicleNumberTest` | unit | BR-01 normalization (`DHK-1234`, `dhk 1234`, ` DHK1234 `, tabs, Bangladeshi formats) |
| `VehicleServiceTest` | unit (Mockito) | TC-01, TC-02, TC-03, TC-04, BR-02 (other vehicle's number → 409), 404s, DB-race → same 409, TC-11, sort whitelist; ADR 0023: older version → `StaleVersionException` before any check or save, `version` in the response |
| `LoginAttemptServiceTest` | unit (moving test Clock) | ADR 0022: 5th failure locks the e-mail for 15 min (from every IP), 20 failures block the IP, window and lock end, refused attempts not counted (lock not extended), success clears the e-mail and gives back only its own IP count, the attempt that reaches the limit may still succeed, release gives both counts back, longer lock reported, 10,000-key bound (expired entries purged first); security review: a locked e-mail is never evicted, an e-mail close to the limit outlives sprayed single failures, a full map of locks counts a new e-mail under its IP only, 50 parallel attempts at count 4 get exactly one try, no lost updates, CR/LF and long keys made log-safe, start-up fails on values ≤ 0 |
| `AuthServiceTest` | unit (Mockito) | ADR 0022 order: a locked e-mail gets 429 without a user lookup or password check; wrong password, unknown e-mail and disabled user all count; success clears the count; 10 parallel logins at count 4 → one `PasswordEncoder.matches` call, 9 × 429 (security review F1); a database failure is not counted |
| `ClientIpApiTest` | API (real Tomcat, random port) | Security review SEC-1: the IP key is the right-most `X-Forwarded-For` entry from a trusted proxy; a client-added left-most entry or a `Forwarded: for=` header can neither block a chosen IP nor dodge the client's own limit |
| `JwtServiceTest` | unit | issue/parse, expiry, foreign signature, garbage, fail-fast on short secret |
| `PageRequestsTest`, `ManufactureYearValidatorTest` | unit | size cap, out-of-range page, sort parsing, LIKE escaping; BR-03 year bounds with a fixed Asia/Dhaka Clock |
| `GlobalExceptionHandlerTest`, `CorsPropertiesTest` | unit | unique-constraint race → same 409, other integrity violations → generic 409, database unreachable → 503, unexpected → 500 without details, stale version and lost optimistic-locking race → 409 `stale-version` without `errors`, 429 with `Retry-After` rounded up (at least 1 s, minutes rounded up); `*` CORS origin rejected |
| `VehicleApiTest` | API (MockMvc + PostgreSQL) | TC-01 (201 + Location), TC-02, TC-03, TC-04, TC-05, TC-06, TC-07 (STAFF 403 / ADMIN 204), TC-11, sorting, paging, search, validation, CORS |
| `AuthApiTest` | API | login OK, wrong password / unknown e-mail / disabled user → 401 generic, blank fields 400, `/auth/me`, expired and garbage token 401, public health + docs (incl. the documented status codes) |
| `LoginThrottlingApiTest` | API | ADR 0022 / contract 6.1: 6th attempt → 429 Problem Details + `Retry-After`, a correct password while locked → 429, lock applies from every IP and to the normalized e-mail, unknown e-mail locked like an existing one, success clears the count, 20 failures block the IP, blank fields not counted, `Retry-After` readable through CORS |
| `VehicleOptimisticLockingApiTest` | API | ADR 0023 / contract 6.2: `version` in POST ignored (0), PUT bumps it, second save with the old version → 409 `stale-version` and nothing changed, newer version also 409, stale wins over a duplicate number, unchanged PUT keeps the version, missing/null `version` → 400 field error, a concurrent commit between read and flush → `ObjectOptimisticLockingFailureException`, migration V2_1 column |
| `VehicleListPerformanceTest` | performance (MockMvc + pooled PostgreSQL) | NFR guideline 6: 1,000 vehicles seeded by JDBC batch; default list, deep page 49, size 100, number searches, sorts — p95 < 500 ms asserted. Results: [`../docs/PERFORMANCE.md`](docs/PERFORMANCE.md) |

CI: `.github/workflows/ci.yml` runs `mvn -B verify` on every push and pull request (Temurin 21, Maven cache) and
uploads the test and coverage reports.

## Features

| Feature | FR | Status |
|---|---|---|
| Log in with email and password (JWT, generic 401) | FR-AUTH-01 | Done |
| Session expiry (60 min token, 401 on missing/expired token) | FR-AUTH-02 | Done (logout = client discards token) |
| Vehicle list from the database, server-side paging/sorting, number search | FR-V01 | Done |
| Add vehicle, server validation, 201 + Location | FR-V02 | Done |
| Edit vehicle, updatedAt changes | FR-V03 | Done |
| Delete vehicle, ADMIN only (STAFF → 403) | FR-V04 | Done (BR-10 deferred to v2.0, see below) |
| Duplicate vehicle number validation (normalized, service + DB constraint) | FR-V05 | Done |
| Real data only (demo data is seeded into the real DB, local profile only) | FR-V06 | Done |
| Audit fields createdAt / updatedAt / createdBy | FR-X01 | Done for users and vehicles |
| Login throttling: 5 failures per e-mail / 20 per IP in 15 min → 429 + `Retry-After` for 15 min | NFR-SEC-01, ADR 0022 | Done (v1.1) |
| Optimistic locking on vehicle edit: `version` in responses, required in PUT, 409 `stale-version` | NFR-REL-01, ADR 0023 | Done (v1.1) |
| Documents, maintenance, status action, filters, drivers, dashboard | FR-D01…FR-DB01 | Planned for v2.0 |

## Decisions and trade-offs

This is a short summary. Each decision, with the alternatives considered and its consequences, is an ADR in [`../docs/adr/`](docs/adr/README.md).

- **PostgreSQL everywhere.** Same SQL locally, in tests (embedded PostgreSQL) and on Render; v2.0 needs partial
  unique indexes for driver assignments, which only PostgreSQL offers.
- **Flyway + `ddl-auto=validate`.** Schema changes are versioned files; Hibernate only checks the schema.
- **Duplicate rule enforced twice** (BR-01). The service normalizes the number (trim, uppercase, remove whitespace
  and hyphens), checks `existsByNumberKey` (on update `existsByNumberKeyAndIdNot`, BR-02) and returns a clear 409.
  The `uk_vehicles_number_key` UNIQUE constraint catches races; the resulting `DataIntegrityViolationException`
  becomes the same 409. The number is stored as entered (`vehicle_number`) and normalized (`number_key`).
- **Free-text vehicle numbers.** Bangladeshi formats vary (`DHAKA METRO-GA 11-2345`), so only "required, ≤ 40
  chars, at least one letter or digit" is validated. Search matches both the number as entered and the normalized
  key, so `dhk1234` finds `DHK-1234`.
- **Pagination from day one.** `PageResponse` is a stable wrapper (Spring's `Page` JSON is not a contract). Size is
  capped at 100 (TC-11), sort fields are whitelisted (unknown → 400). v2.0 adds status/type filters as further
  JPA Specifications.
- **Problem Details everywhere**, with an `errors` array for field errors and duplicates; no stack traces or
  exception messages are ever returned. This includes Spring's own errors (404, 405, 415) and errors raised
  outside Spring MVC, such as a URL rejected by the security firewall (`/error` is replaced by
  `ProblemDetailsErrorController`). Each feature registers its unique constraints as `UniqueConstraintRule`
  beans, so a lost race on the database constraint returns the same 409 as the service check.
  An unreachable database or exhausted connection pool returns **503** (`internal`, "Service unavailable") with a
  one-line WARN log instead of a 500 with a stack trace. JSON responses of 2 KB or more are gzip-compressed.
- **Injected `Clock` (Asia/Dhaka).** Used for JWT timestamps, audit timestamps and the "next year" bound of the
  manufacture year, so date logic is deterministic in tests — the base for v2.0 expiry rules (BR-08).
- **Stateless JWT (HS256, 60 min)**, BCrypt password hashes, `@PreAuthorize("hasRole('ADMIN')")` on delete. The
  first ADMIN comes from environment variables at start-up, never from a migration.
- **Token storage in the browser (XSS trade-off).** The Flutter Web client keeps the JWT in browser storage, which
  any script running on the page could read. Accepted for this course because tokens are short-lived (60 min),
  there is no third-party script on the page and there is no refresh token. A production system would use an
  HttpOnly, SameSite cookie with CSRF protection, or a backend-for-frontend.
- **Login throttling in memory (ADR 0022, amended after the security review of 2026-11-08).** `LoginAttemptService`
  counts failed logins per normalized e-mail and per client IP with the injected `Clock`. Each attempt is reserved
  (lock check + count in one atomic step) before the password check, so parallel requests cannot test more than the
  limit; a success clears the e-mail and gives back its own IP count. At most 10,000 keys per map, expired entries
  removed on access; when full, the unlocked entry with the fewest failures is evicted, **never a locked one**.
  While locked the password is not checked, and unknown e-mails behave like existing ones. Logged keys have control
  characters replaced (no forged log lines). The state is per instance and lost on restart (Render free runs one
  instance); a shared store would be the follow-up. The IP comes from Tomcat's `RemoteIpValve`
  (`server.forward-headers-strategy=native`): `X-Forwarded-For` is read only from a trusted internal proxy and the
  right-most entry that is not such a proxy is used — the address Render's proxy appended. Client-added entries and
  the `Forwarded` header are ignored, so a spoofed header can neither block someone else's IP nor dodge the client's
  own limit (with the earlier `framework` setting it could do both).
- **Optimistic locking on vehicles (ADR 0023).** `vehicles.version` (V2_1) is a JPA `@Version`. The service compares
  the sent `version` with the stored one before any other check and answers 409 `stale-version`; a race between that
  check and the flush ends in Hibernate's versioned UPDATE matching no row, which the handler maps to the same 409.
  A PUT that changes no value writes nothing, so the version stays.
- **BR-10 deferred to v2.0.** In v1.0 a vehicle has no documents, maintenance or assignments, so delete is a hard
  delete. When those tables arrive (with `ON DELETE RESTRICT` foreign keys), delete will return 409 and suggest
  setting the vehicle INACTIVE.
- **Demo data is real data.** The `dev/DemoDataSeeder` inserts 26 vehicles into the database (local profile only,
  only when the table is empty); the frontend has no mock lists.

## Known limitations / next steps

- No refresh tokens and no server-side logout/blacklist: a token stays valid until it expires.
- No user management endpoints yet (accounts come from start-up configuration).
- Login throttling is in memory and per instance (reset by a restart, not shared between instances). Anyone who
  knows an e-mail can keep it locked (5 wrong passwords every 15 minutes); users behind one NAT address share the
  20-failure IP budget. The IP key trusts the right-most `X-Forwarded-For` entry added by a proxy in the internal
  address ranges; a CDN with a public address in front of Render would need `SERVER_TOMCAT_REMOTEIP_TRUSTED_PROXIES`.
- Status changes (`PATCH /vehicles/{id}/status`), status/type filters, search on brand/model/driver, documents,
  maintenance, drivers and the dashboard summary are v2.0 work.
- The embedded-PostgreSQL launcher (`spring-boot:test-run`) is for development only; its data is temporary.

## Deploy to Render

The repository contains a Render Blueprint (`render.yaml`) and a multi-stage `Dockerfile`.

1. Push the repository to GitHub. In Render choose **New → Blueprint** and select the repository; it creates the
   PostgreSQL database `stark-vms-db` and the Docker web service `stark-vms-backend` (health check
   `/actuator/health`, `JWT_SECRET` generated). Without Blueprints: create a PostgreSQL instance, then a Web
   Service with runtime *Docker* from this repository.
2. **Convert the database URL.** Render shows a connection string like
   `postgres://vms:PASSWORD@dpg-xxxx-a.singapore-postgres.render.com/vms`. Spring needs the JDBC form without the
   credentials:
   - `DB_URL` = `jdbc:postgresql://dpg-xxxx-a.singapore-postgres.render.com:5432/vms`
     (use the *internal* host `dpg-xxxx-a` when the service and DB are in the same region)
   - `DB_USERNAME` = `vms`, `DB_PASSWORD` = `PASSWORD` (the Blueprint fills these two from the database)
3. Set the remaining variables in the dashboard: `CORS_ALLOWED_ORIGINS=https://<your-site>.netlify.app`,
   `APP_ADMIN_EMAIL`, `APP_ADMIN_PASSWORD` (and optionally the `APP_STAFF_*` values). If you do not use the
   Blueprint, also set `JWT_SECRET` (≥ 32 bytes). Do **not** set `SPRING_PROFILES_ACTIVE=local` in production.
4. Deploy. Render injects `PORT`; the app binds to it (`server.port=${PORT:8080}`). Flyway creates the schema on
   first start and the bootstrap admin is created.
5. Smoke-test: `GET /actuator/health` → `{"status":"UP"}`, log in through Swagger UI or the Postman collection,
   list and create a vehicle, and try a duplicate (409).
6. After the frontend is on Netlify, make sure its URL is in `CORS_ALLOWED_ORIGINS` and redeploy.

Build and run the image locally (needs a reachable PostgreSQL):

```bash
docker build -t stark-vms-backend .
docker run -p 8080:8080 -e DB_URL=jdbc:postgresql://host.docker.internal:5432/vms \
  -e DB_USERNAME=postgres -e DB_PASSWORD=postgres -e JWT_SECRET="$(openssl rand -base64 48)" \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5000 -e APP_ADMIN_EMAIL=admin@stark.local \
  -e APP_ADMIN_PASSWORD=Admin@12345 stark-vms-backend
```
