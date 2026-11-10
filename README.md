# Stark Group VMS — Backend

> ### Stark Group VMS — one project, two repositories
>
> The fleet management system is split the way it is deployed, as the course guideline (Section 9)
> asks. To see or run the whole thing you need both; to simply *use* it, open the live site.
>
> | Repository | What is in it |
> |---|---|
> | [`stark-vms-backend`](https://github.com/AvishekBis/stark-vms-backend) | Spring Boot 3.5 / Java 21 REST API, PostgreSQL, Flyway, JWT **(you are here)** |
> | [`stark-vms-frontend`](https://github.com/AvishekBis/stark-vms-frontend) | Flutter Web client, Riverpod, go_router |
>
> **Live site:** <https://stark-vms-avishek.netlify.app> · **API:** <https://stark-vms-backend-i2i2.onrender.com/api/v1> · **API docs:** <https://stark-vms-backend-i2i2.onrender.com/swagger-ui.html>
>
> Demo logins and the whole feature list are further down this page. The API is on Render's free
> tier and sleeps when idle, so the first request can take up to a minute.

Fleet management REST API for Stark Group, **version 2.0.0 (Assignment 2, "Advanced Fleet Management")**.

- **v1.0 (Assignment 1):** login with JWT, vehicle register (list / add / edit / delete) and duplicate vehicle
  number validation, all on a real PostgreSQL database.
- **v2.0 (Assignment 2):** vehicle status management, server-side search / filters / sorting / paging, drivers,
  driver-vehicle assignment with history, vehicle documents (upload, view, expiry tracking), maintenance records
  and history with total cost, and a one-call dashboard. v2.0 extends the v1.0 code; every v1.0 test still runs.
- **v2.1 (hardening, ADRs 0022–0024):** login throttling (429 + `Retry-After`), optimistic locking (`version` on
  vehicles, drivers, maintenance records and users; 409 `stale-version`), and ADMIN user management (`/users`, own
  password change, tokens re-checked against the database on every request). Migration `V7`; no new dependencies.
- **v2.1 security review (2026-11-08):** client IP from the right-most proxy-added `X-Forwarded-For` entry (Tomcat
  `RemoteIpValve`), login attempts reserved before the password check (no overshoot under parallel requests), locked
  entries never evicted, log-safe keys, millisecond token cut-off, tokens revoked on disable (migration `V8`), wrong
  current passwords throttled.

## Live

| | URL |
|---|---|
| Frontend | <https://stark-vms-avishek.netlify.app> |
| API base | <https://stark-vms-backend-i2i2.onrender.com/api/v1> |
| API docs (Swagger UI) | <https://stark-vms-backend-i2i2.onrender.com/swagger-ui.html> |
| Health | <https://stark-vms-backend-i2i2.onrender.com/actuator/health> |

Demo accounts on the live site (throw-away credentials, not used anywhere else):

| Role | Email | Password |
|---|---|---|
| ADMIN | admin@stark.local | `Wildstone99@b` |
| STAFF | staff@stark.local | `Wildstone98@b` |

Signing in as STAFF shows the role rules: deleting a vehicle and the Users screen are ADMIN-only, and the API
answers 403.

**The free Render instance sleeps after a while, so the first request can take up to a minute.** The app shows a
"Connecting to server…" message meanwhile. Open the site once before reviewing it.

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
| HTTP client (Supabase storage option) | Spring `RestClient` (part of Spring Framework, no extra dependency) | 6.2.11 |

v2.0 adds **no new dependencies**; the project version is `2.0.0` (`pom.xml`).

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

The code is organised **by feature** (`auth/`, `vehicle/`, `driver/`, `assignment/`, `document/`, `maintenance/`,
`dashboard/`) with shared infrastructure in `config/` and `common/`. Each request passes Controller → Service →
Repository; controllers only map HTTP, services own every rule and transaction, and JPA entities never leave the
service layer (DTOs are Java records). Every error, including 401/403 from the security filters and 413 from the
multipart parser, leaves the API in one format: RFC 9457 Problem Details.

```
src/main/java/com/starkgroup/vms/
├── VmsApplication.java
├── config/        SecurityConfig, CorsConfig(+Properties), OpenApiConfig, ClockConfig (Asia/Dhaka), JpaAuditingConfig,
│                  ValidationConfig (Bean Validation uses the same Clock)
├── common/
│   ├── dto/         PageResponse (stable page wrapper)
│   ├── exception/   GlobalExceptionHandler, ApiErrorType, UniqueConstraintRule, NotFound/DuplicateResource/
│   │                BusinessRule/BadRequest/FieldValidation/FileTooLarge/StaleVersion/TooManyLoginAttempts
│   │                exceptions
│   ├── expiry/      ExpiryStatus, ExpiryCalculator (BR-08, injected Clock, Asia/Dhaka, 30-day window), ExpiryInfo
│   ├── persistence/ AuditableEntity (createdAt, updatedAt, createdBy — FR-X01)
│   ├── util/        PageRequests (size cap + sort whitelist), LikePatterns, TextValues
│   ├── validation/  @ManufactureYear (uses the injected Clock)
│   └── web/         RequestLoggingFilter, ProblemResponseWriter, ProblemDetailsErrorController
├── auth/          AuthController (+ PUT /me/password), AuthService, JwtService, JwtAuthenticationFilter,
│                  TokenAuthenticator (user re-checked per request), LoginAttemptService (+LoginThrottleProperties),
│                  UserController, UserService, UserRepository, UserSpecifications, UserSortField,
│                  UserConstraintConfig, PasswordPolicy (+@ValidPassword), User, Role, AdminBootstrap, dto/
├── vehicle/       VehicleController (+ PATCH /status), VehicleService, VehicleRepository (status counts),
│                  VehicleUsage (BR-10: implemented by document/, maintenance/ and assignment/), Vehicle,
│                  VehicleNumber, VehicleMapper, VehicleSpecifications (search incl. current driver name, status,
│                  type), VehicleSortField, VehicleConstraintConfig, enums, dto/
├── driver/        DriverController, DriverService, DriverRepository, Driver, DriverStatus, DriverPhone,
│                  LicenseNumber, @ValidDriverPhone, DriverSpecifications, DriverSortField, DriverMapper,
│                  DriverConstraintConfig, dto/
├── assignment/    AssignmentController, AssignmentService, VehicleDriverAssignment(+Repository),
│                  AssignmentMapper, AssignmentConstraintConfig, dto/
├── document/      DocumentController, DocumentService, VehicleDocument(+Repository), DocumentType, FileValidator,
│                  DocumentMapper, dto/, storage/ (StorageService, DatabaseStorageService, SupabaseStorageService,
│                  StoredFile(+Repository), StorageProperties)
├── maintenance/   MaintenanceController, MaintenanceService, MaintenanceRecord(+Repository), MaintenanceType,
│                  MaintenanceMapper, dto/
├── dashboard/     DashboardController, DashboardService (COUNT/SUM queries only), dto/
└── dev/           DemoDataSeeder + DemoFiles (only when app.seed-demo-data=true — local profile)
src/main/resources/db/migration/  V1__create_users.sql, V2__create_vehicles.sql, V2_1__add_vehicle_version.sql,
                                  V3__create_drivers.sql,
                                  V4__create_assignments.sql, V5__create_documents.sql, V6__create_maintenance.sql,
                                  V7__add_version_and_user_management.sql, V8__add_users_tokens_revoked_at.sql
```

### Database (ER, v2.0)

```mermaid
erDiagram
    vehicles ||--o{ vehicle_driver_assignments : "has (history)"
    drivers ||--o{ vehicle_driver_assignments : "drives (history)"
    vehicles ||--o{ vehicle_documents : has
    vehicles ||--o{ maintenance_records : has
    vehicle_documents ||--|| stored_files : "storage_key (no FK)"
    users {
        bigint id PK
        varchar email UK
        varchar role "ADMIN | STAFF"
        boolean enabled
        timestamptz password_changed_at "V7, NULL = never"
        timestamptz tokens_revoked_at "V8, set on disable"
        bigint version "V7"
    }
    vehicles {
        bigint id PK
        varchar vehicle_number
        varchar number_key UK "normalized"
        varchar status "ACTIVE | INACTIVE | MAINTENANCE"
    }
    drivers {
        bigint id PK
        varchar phone UK "normalized"
        varchar license_number
        varchar license_key UK "normalized"
        date license_expiry
        varchar status "ACTIVE | INACTIVE"
    }
    vehicle_driver_assignments {
        bigint id PK
        bigint vehicle_id FK
        bigint driver_id FK
        timestamptz assigned_at
        timestamptz unassigned_at "NULL = current"
    }
    vehicle_documents {
        bigint id PK
        bigint vehicle_id FK
        varchar document_type
        date issue_date
        date expiry_date "CHECK >= issue_date"
        varchar storage_key UK "random UUID"
        varchar file_name "sanitized original"
    }
    stored_files {
        bigint id PK
        varchar storage_key UK
        bytea data
    }
    maintenance_records {
        bigint id PK
        bigint vehicle_id FK
        date service_date
        numeric cost "NUMERIC(12,2), CHECK >= 0"
    }
```

- Every table has `id BIGINT GENERATED ALWAYS AS IDENTITY`, `created_at`, `updated_at` (TIMESTAMPTZ) and
  `created_by` (FR-X01). Enum-like columns are `VARCHAR` + `CHECK`. Money is `NUMERIC(12,2)`, dates are `DATE`.
- All foreign keys are `ON DELETE RESTRICT`, so history can never silently disappear (BR-10).
  `vehicle_documents → stored_files` has no foreign key: files may live in Supabase instead, and `DocumentService`
  deletes the stored file together with the document.
- BR-06: partial unique indexes `uq_assignment_open_vehicle` / `uq_assignment_open_driver`
  (`WHERE unassigned_at IS NULL`) allow one current assignment per vehicle and per driver.
- Indexes: `vehicle_documents(expiry_date)`, `maintenance_records(vehicle_id, service_date DESC)` and
  `(service_date)`, assignments `(vehicle_id, assigned_at DESC)` / `(driver_id, assigned_at DESC)`,
  `drivers(full_name)` / `(license_expiry)`.
- Expiry status is never stored; it is computed from the date on every read (BR-08).
- v2.1 (`V2_1`, shared with v1.1): `vehicles.version`; (`V7`): `version BIGINT NOT NULL DEFAULT 0` on `drivers`, `maintenance_records` and `users`
  (JPA `@Version`, ADR 0023) and `users.password_changed_at` (ADR 0024). `V8`: `users.tokens_revoked_at`, set when
  an account is disabled, so re-enabling it does not revive older tokens (ADR 0024, security review).

## Run locally

### Option A — no PostgreSQL installation needed (embedded PostgreSQL)

```bash
./mvnw spring-boot:test-run          # Windows: mvnw.cmd spring-boot:test-run
```

`src/test/java/.../TestVmsApplication.java` starts a real PostgreSQL 17 server from bundled binaries on port
**5433**, runs the Flyway migrations, activates the `local` profile and seeds the demo data plus the two demo
accounts: 26 vehicles, 12 drivers (9 currently assigned, closed history rows, 1 expired and 2 expiring licences),
20 documents (3 expired, 5 expiring within 30 days, 12 valid — tiny generated PDF/PNG files) and 30 maintenance
records over the last six months (6 in the current month). All demo dates are relative to today, so the dashboard
always shows interesting numbers. The API is on <http://localhost:8080>, Swagger UI on <http://localhost:8080/swagger-ui.html>. Data is
temporary and recreated on every start. First start downloads dependencies and takes about a minute.
To run a second instance next to it, pick other ports, e.g.
`LOCAL_PG_PORT=5444 ./mvnw spring-boot:test-run -Dspring-boot.run.arguments=--server.port=8091`.

### Option B — your own PostgreSQL

1. Create a database: `createdb vms` (or `CREATE DATABASE vms;` in psql).
2. Copy `application-local.example.yml` to `application-local.yml` (git-ignored) in the project root and adjust
   the values.
3. `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`

The `local` profile seeds demo data (vehicles when the `vehicles` table is empty; drivers, assignments,
documents and maintenance when those tables are empty) and allows CORS from any
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
| `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` | first start | empty | First ADMIN, created at start-up only if the e-mail does not exist (password 8 characters to 72 bytes, the same policy as `/users`) |
| `APP_ADMIN_FULL_NAME` | no | `Fleet Admin` | Display name of that admin |
| `APP_STAFF_EMAIL` / `APP_STAFF_PASSWORD` / `APP_STAFF_FULL_NAME` | no | empty / `Fleet Officer` | Optional STAFF account, same rule |
| `APP_SECURITY_LOGIN_MAX_FAILURES_PER_EMAIL` | no | `5` | Failed logins for one e-mail within the lockout window that lock the e-mail (ADR 0022) |
| `APP_SECURITY_LOGIN_MAX_FAILURES_PER_IP` | no | `20` | Failed logins from one client IP within the window that block the IP |
| `APP_SECURITY_LOGIN_LOCKOUT` | no | `15m` | Lock length and counting window (Spring duration, e.g. `15m`, `900s`); values ≤ 0 stop the start-up |
| `SPRING_PROFILES_ACTIVE` | no | – | `local` for development only (demo data, localhost CORS) |
| `APP_STORAGE_PROVIDER` | no | `database` | Where uploaded document files are stored: `database` (table `stored_files`, BYTEA) or `supabase` |
| `SUPABASE_URL` | with `supabase` | empty | Project URL, e.g. `https://<project>.supabase.co` |
| `SUPABASE_SERVICE_KEY` | with `supabase` | empty | Service-role key (server only, never sent to the browser) |
| `SUPABASE_BUCKET` | no | `vehicle-documents` | Private Storage bucket name |
| `APP_STORAGE_KEY_PREFIX` | no | empty (`render.yaml`: `prod/`) | Path prefix for objects in the Supabase bucket, so dev and prod can share one bucket; only the object path changes, the storage key saved with the document stays the same. Letters, digits and `. _ - /` only. Set it before the first upload, because objects stored earlier keep their old path |

Login throttling state is kept in memory per instance and is reset by a restart (one instance on Render free). The
client IP comes from Tomcat's `RemoteIpValve` (`server.forward-headers-strategy: native`): it reads
`X-Forwarded-For` only from a trusted internal proxy (Spring Boot's default private ranges; override with
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`) and takes the right-most entry that is not such a proxy, the address
Render's proxy appended. Client-added entries and the `Forwarded` header are ignored, so a spoofed header can neither
block someone else's IP nor dodge the client's own limit (corrected after the security review; with the earlier
`framework` setting a client could block any chosen IP). Locked e-mails and IPs are never evicted from the 10,000-key
maps. If a CDN with its own public address is ever put in front of Render, add it to
`SERVER_TOMCAT_REMOTEIP_TRUSTED_PROXIES`, otherwise all its clients share one IP budget.

Upload limits are fixed in `application.yml`: 5 MB per file (`spring.servlet.multipart.max-file-size`), 6 MB per
request; anything larger returns **413** `file-too-large`.

Generate a secret with e.g. `openssl rand -base64 48`.

## API summary

Full contract: [`../docs/API_CONTRACT.md`](docs/API_CONTRACT.md) (section 6 = v2.0, section 7 = v2.1) and Swagger UI (every
endpoint has a summary, examples on the DTOs and the documented error responses). Postman collection:
[`docs/postman_collection.json`](docs/postman_collection.json) — requests 01–12 for v1.0, 13–33 for v2.0
(dashboard, search/filters, drivers, assignment + TC-10, document upload/view, maintenance, status, BR-10), 34–39 for
v2.1 (stale version, users, own password).

All paths start with `/api/v1`.

| Method | Path | Role | FR |
|---|---|---|---|
| POST | `/auth/login` (v2.1: 429 + `Retry-After` when throttled) | public | AUTH-01 |
| GET | `/auth/me` | any | AUTH-02 |
| PUT | `/auth/me/password` → 204 (wrong current password → 400) | any | v2.1 |
| GET | `/vehicles?search=&status=&type=&page=&size=&sort=` | any | V01, V08, V09 |
| GET | `/vehicles/{id}` (incl. `currentDriver`) | any | V01 |
| POST | `/vehicles` → 201 + `Location` | any | V02, V05 |
| PUT | `/vehicles/{id}` (`version` required, 409 `stale-version`) | any | V03, V05 |
| PATCH | `/vehicles/{id}/status` | **ADMIN** | V07 |
| DELETE | `/vehicles/{id}` → 204, or 409 `vehicle-has-history` | **ADMIN** | V04, BR-10 |
| GET / POST | `/drivers?search=&status=&available=&page=&size=&sort=` / `/drivers` | any | DR01 |
| GET / PUT | `/drivers/{id}` (PUT: `version` required) | any | DR01 |
| DELETE | `/drivers/{id}` → 204, or 409 `driver-has-history` | **ADMIN** | DR01 |
| PUT / DELETE | `/vehicles/{id}/driver` (assign or change / unassign) | any | DR02 |
| GET | `/vehicles/{id}/assignments`, `/drivers/{id}/assignments` | any | DR02 |
| GET / POST | `/vehicles/{id}/documents` (POST is `multipart/form-data`) | any | D01 |
| GET | `/documents/{docId}`, `/documents/{docId}/file?download=` | any | D01 |
| DELETE | `/documents/{docId}` | **ADMIN** | D01 |
| GET | `/documents/expiring?withinDays=30&page=&size=` | any | D02 |
| GET / POST | `/vehicles/{id}/maintenance` (history + `totalCost`) | any | M01, M02 |
| GET / PUT | `/maintenance/{recordId}` (PUT: `version` required) | any | M01 |
| DELETE | `/maintenance/{recordId}` | **ADMIN** | M01 |
| GET | `/dashboard/summary` | any | DB01, X02 |
| GET / POST | `/users?search=&role=&enabled=&page=&size=&sort=` / `/users` → 201 + `Location` | **ADMIN** | v2.1 |
| GET / PUT | `/users/{id}` (PUT: name, role, enabled, `version`; no DELETE — disable instead) | **ADMIN** | v2.1 |
| POST | `/users/{id}/password` → 204 (admin reset) | **ADMIN** | v2.1 |

## Tests

```bash
./mvnw verify
```

Runs **366 tests** (all green: 253 from v2.0, which keep the v1.0 tests as regression tests, plus 93 for v2.1,
19 for the fixes after the security review of 2026-11-08 and 1 v1 → v2 migration-upgrade test) and writes a coverage report to
`target/site/jacoco/index.html` (97.2 % line and 83.1 % branch coverage overall; `vehicle`
99 %, `driver` 98 %, `assignment` 98 %, `auth` 98 %, `document` 97 %, `maintenance` 100 %, `dashboard` 100 %,
`common.expiry` 100 %). Saved evidence of the last run
(per test class and per service): [`../docs/test-reports/backend-summary.md`](docs/test-reports/backend-summary.md).
API tests run against a real
embedded PostgreSQL (zonky) with the Flyway schema, so constraints such as `uk_vehicles_number_key`,
`uk_drivers_phone`, the partial unique indexes `uq_assignment_open_*` and the CHECK constraints are exercised — no
Docker required, locally or in CI. Date rules are tested with `Clock.fixed(...)` in unit tests and with dates
relative to "today" (Asia/Dhaka) in API tests, so results do not depend on the day the tests run.
The compiler runs with `-Xlint:all` (main and test code) and the build shows no javac warnings.

| Class | Level | Tests | Covers |
|---|---|---|---|
| `VehicleNumberTest` | unit | 12 | BR-01 normalization (`DHK-1234`, `dhk 1234`, ` DHK1234 `, tabs, Bangladeshi formats) |
| `VehicleServiceTest` | unit (Mockito) | 21 | TC-01..TC-04, BR-02, 404s, PUT loads with the row lock, DB race → same 409, TC-11, sort whitelist; FR-V07 INACTIVE closes the assignment, MAINTENANCE keeps it, same status no-op; BR-10 through `VehicleUsage`; ADR 0023 stored version accepted, older version refused before anything changes |
| `JwtServiceTest` | unit | 10 | issue/parse (user id and issue time), expiry, foreign signature, garbage, fail-fast on short secret and non-positive expiry; security review SEC-3: issue time kept to the millisecond (`iat_ms`), a token without it falls back to `iat` |
| `PageRequestsTest`, `ManufactureYearValidatorTest` | unit | 6 + 2 | size cap, sort parsing, LIKE escaping; BR-03 year bounds with a fixed Asia/Dhaka Clock |
| `GlobalExceptionHandlerTest`, `CorsPropertiesTest` | unit | 14 + 2 | unique-constraint race → same 409 (incl. partial index → driver-already-assigned), business rule with field error, multipart limit → 413, database unreachable and storage failure → 503, unexpected → 500 without details; v2.1: `stale-version` 409 without `errors[]`, lost optimistic-lock race → same 409 naming the record, 429 + `Retry-After` rounded up (at least 1 s, minutes rounded up), field error with a specific detail; `*` CORS origin rejected |
| `ExpiryCalculatorTest` | unit | 5 | BR-08: TC-08 (10 days → EXPIRING_SOON), TC-09 (yesterday → EXPIRED), today → EXPIRING_SOON (0 days), 30 days → EXPIRING_SOON, 31 → VALID, "today" in Dhaka with a UTC clock |
| `DriverPhoneTest`, `LicenseNumberTest` | unit | 12 + 5 | phone normalization (+880, spaces, hyphens, parentheses) and validity; licence normalization |
| `DriverServiceTest` | unit (Mockito) | 13 | duplicate phone / licence (normalized), update ignores self, DB race → same 409, INACTIVE closes the assignment, delete guard, licence status (FR-X02); ADR 0023 stale version refused before the assignment is closed |
| `AssignmentServiceTest` | unit (Mockito) | 11 | TC-10, inactive vehicle / driver, idempotent re-assign, change driver closes previous (flush before insert), partial-index race, unassign / no-current-driver, 404s |
| `FileValidatorTest` | unit | 14 | BR-07: PDF/PNG/JPEG accepted, magic bytes vs extension mismatch, content type mismatch, other types, missing/empty, > 5 MB, exactly 5 MB, file-name sanitizing |
| `DocumentServiceTest` | unit (Mockito) | 8 | random UUID key, sanitized name, expiry < issue → 400 before storing, equal dates OK, stored file removed when metadata save fails, TC-08, TC-09, expiring window, delete removes file |
| `ContentDispositionsTest` | unit | 5 | `Content-Disposition` inline / attachment, ASCII fallback name plus RFC 5987 `filename*` for non-ASCII names, quotes escaped, control characters cannot inject headers |
| `SupabaseStorageServiceTest` | unit (MockRestServiceServer) | 7 | upload/download/delete REST calls with service key, 404 on delete ignored, errors → StorageException, `APP_STORAGE_KEY_PREFIX` in the object path only, unsafe prefix and missing config fail fast |
| `ExpiryOnOrAfterIssueTest` | unit (Bean Validation) | 2 | BR-07 on the upload DTO: expiry < issue → field error on `expiryDate`; same day, later and no issue date valid |
| `MaintenanceServiceTest` | unit (Mockito) | 9 | BR-09 future date (Dhaka "today" with a fixed clock), negative cost, > 2 decimals, zero cost OK, history order + total over all records, update; ADR 0023 older version refused |
| `MaintenanceRequestValidationTest` | unit (standalone MockMvc) | 2 | BR-09 on the DTO with a fixed Clock: tomorrow in Dhaka → 400 field error `serviceDate` before the service runs; today in Dhaka (still yesterday in UTC) → 201 |
| `DashboardServiceTest` | unit (Mockito) | 4 | totals from GROUP BY counts, missing statuses = 0, document windows, "this month" = Dhaka month at a UTC month boundary, driver/licence counts |
| `VehicleApiTest` | API | 30 | v1.0: TC-01..TC-07, TC-11, sorting, paging, search, validation, CORS; v2.0: FR-V07 status PATCH ADMIN 200 / STAFF 403 / invalid 400 / 404, FR-V08 search by brand, model and current driver name, FR-V09 status/type filters AND-ed, invalid enum 400, BR-10 409 (maintenance, closed assignment, document) |
| `DriverApiTest` | API | 12 | FR-DR01 CRUD, 201 + Location, 409 phone and licence incl. normalized variants, DB constraints, validation, STAFF delete 403, history delete 409, search (name/phone/licence), status filter, sort, available=true |
| `AssignmentApiTest` | API | 14 | assign, currentDriver in detail and list, idempotent, change driver closes previous with history kept, TC-10 409, partial unique indexes, unassign 204 / no-current-driver 409, INACTIVE vehicle / driver 409, status INACTIVE auto-closes, MAINTENANCE keeps driver, driver INACTIVE auto-closes, 400/404 |
| `DocumentApiTest` | API | 13 | upload PDF 201 + Location (UUID key, bytes in `stored_files`), download bytes + Content-Type + Content-Disposition inline/attachment + nosniff, 401 without token, TC-08/TC-09 badges and order, expiring endpoint (incl. expired, withinDays 0/90/366), wrong type 400, > 5 MB 413, expiry < issue 400, missing parts / bad values 400, DB CHECK, STAFF delete 403 / ADMIN 204, CORS exposes Content-Disposition |
| `MaintenanceApiTest` | API | 6 | FR-M01 CRUD (STAFF delete 403), FR-M02 newest first + totalCost/recordCount across pages, empty history, future date 400, negative cost / other fields 400, DB CHECK |
| `DashboardApiTest` | API | 3 | TC-12 exact counts and cost for known data (incl. month boundary and top-10 lists), empty DB, seeded demo data = designed numbers = independent SQL counts, seeder idempotent |
| `AuthApiTest` | API | 11 | login OK, wrong password / unknown e-mail / disabled user → 401 generic, blank fields 400, `/auth/me`, expired and garbage token 401, public health + docs (incl. the documented status codes) |
| `LoginAttemptServiceTest` | unit (fixed `MutableClock`) | 19 | ADR 0022: 5th failure locks the e-mail for the lockout, lock ends and counting restarts, failures outside the window do not add up, refused attempts not counted (lock not extended), success clears the e-mail and gives back only its own IP count, the attempt that reaches the limit may still succeed, release gives both counts back, 20 failures block the IP for every e-mail, longer remaining lock wins, a null key is not counted, 10,000-key bound, expired entries purged first; security review: a locked e-mail is never evicted, an e-mail close to the limit outlives sprayed single failures, a map full of locks counts a new e-mail under its IP only, 50 parallel attempts at count 4 get exactly one try, concurrent failures never lost, CR/LF and long keys made log-safe; invalid settings stop the start-up |
| `AuthServiceTest` | unit (Mockito + real throttle) | 9 | locked e-mail refused without checking the password, wrong password / unknown e-mail / disabled user all count, success clears the count, own password change stores the new hash and the Clock time, wrong current password → field error (not 401); security review: 10 parallel logins at count 4 → one `PasswordEncoder.matches` call and 9 × 429 (F1), a database failure is not counted, 5 wrong current passwords → 429 before the password check and login locked too (SEC-7), a correct current password clears the count |
| `PasswordPolicyTest` | unit | 5 | 8 characters minimum, 72 UTF-8 bytes maximum (multi-byte characters counted in bytes), `null` left to `@NotBlank`, the real BCrypt encoder refuses > 72 bytes, bootstrap refuses a password that breaks the policy |
| `TokenAuthenticatorTest` | unit (Mockito) | 7 | ADR 0024: role from the database not the token, deleted / disabled user rejected, token issued before the password change rejected; security review: a token from earlier in the same second as the change is rejected (SEC-3), a later one stays valid, a token from before a disable stays rejected after re-enabling (SEC-5), comparison in milliseconds |
| `JwtAuthenticationFilterTest` | unit (Mockito, servlet mocks) | 4 | principal and authority from `TokenAuthenticator`, revoked token → anonymous with the reason for the 401, database outage during the lookup → 503 Problem Details (never 401, no connection details), no bearer header → no lookup |
| `UserServiceTest` | unit (Mockito) | 16 | create stores the e-mail trimmed + lower-case with a hashed password, duplicate e-mail on field `email`, update / stale version, self-disable and self-demote refused, own rename allowed, last enabled ADMIN never disabled or demoted, other ADMIN can be disabled, disabled ADMIN needs no count, reset stores hash + change time, disabling records the token cut-off from the Clock and enabling keeps it (SEC-5), 404, list default sort + tie-breaker + size cap, unknown sort 400 |
| `UserApiTest` | API | 21 | ADR 0024: STAFF 403 on every `/users` endpoint, 201 + `Location` + normalized e-mail + audit fields (no hash), duplicate e-mail 409, validation incl. password policy, search / role / enabled filters / sort / paging, bad filter values 400, 404, version bump + stale 409, missing `version` 400, self-modification 409, disabled user's token 401 on the next request (login stays generic 401), current DB role wins over the token (demoted ADMIN → 403, forged role claim → 403), deleted user 401, admin reset revokes older tokens and bumps the version, own change (204, old token 401, re-login works), wrong current password 400 keeps the session, re-enabling a user does not revive tokens from before the disable (SEC-5), 5 wrong current passwords → 429 + `Retry-After`, session kept, login locked (SEC-7), `DELETE` → 405, two ADMINs disabling each other at once → one gets `last-admin` |
| `LoginThrottlingApiTest` | API | 7 | ADR 0022: 6th attempt → 429 + `Retry-After` + Problem Details, correct password while locked → 429 (from every IP, any spelling), unknown e-mail locked the same way, success clears the count, 20 failures block the IP, blank fields not counted, CORS exposes `Retry-After` |
| `ClientIpApiTest` | API (real Tomcat, random port) | 2 | Security review SEC-1: the IP key is the right-most `X-Forwarded-For` entry from a trusted proxy; a client-added left-most entry or a `Forwarded: for=` header can neither block a chosen IP nor dodge the client's own limit |
| `OptimisticLockingApiTest` | API | 9 | ADR 0023: `version` in responses and list rows, POST ignores `version`, unchanged PUT keeps it, second save with the old version → 409 `stale-version` and nothing changes, missing `version` → 400 (vehicle, driver, maintenance), status PATCH bumps it, assign / unassign do not, concurrent commit between read and flush → optimistic-lock failure, migration V2_1 + V7 columns |
| `MigrationUpgradeTest` | API | 1 | A database migrated by v1.x (V1, V2, V2_1 in its own schema) validates and upgrades to V8 — the in-place upgrade of the Render database |
| `ListEndpointsPerformanceTest` | performance (MockMvc + pooled PostgreSQL) | 13 | NFR guideline 6: 1,000 vehicles, 300 drivers, 650 assignments, 3,000 documents, 5,000 maintenance rows seeded by JDBC batch; vehicle list / deep page / size 100 / search by number, brand and driver name / status + type + sort, drivers (list, search, available), expiring documents, dashboard — p95 < 500 ms asserted. Results: [`../docs/PERFORMANCE.md`](docs/PERFORMANCE.md) |

CI: `.github/workflows/ci.yml` runs `mvn -B verify` on every push and pull request (Temurin 21, Maven cache) and
uploads the test and coverage reports.

## Features

| Feature | FR | Status |
|---|---|---|
| Log in with email and password (JWT, generic 401) | FR-AUTH-01 | Done |
| Session expiry (60 min token, 401 on missing/expired token) | FR-AUTH-02 | Done (logout = client discards token) |
| Vehicle list from the database, server-side paging/sorting | FR-V01 | Done |
| Add vehicle, server validation, 201 + Location | FR-V02 | Done |
| Edit vehicle, updatedAt changes | FR-V03 | Done |
| Delete vehicle, ADMIN only (STAFF → 403); vehicles with history → 409 (BR-10) | FR-V04 | Done |
| Duplicate vehicle number validation (normalized, service + DB constraint) | FR-V05 | Done |
| Real data only (demo data is seeded into the real DB, local profile only) | FR-V06 | Done |
| Vehicle document upload: 6 types, PDF/JPG/PNG ≤ 5 MB, number + issue/expiry date, view/download | FR-D01 | Done |
| Document expiry tracking: VALID / EXPIRING_SOON (≤ 30 days) / EXPIRED + daysUntilExpiry, expiring list | FR-D02 | Done |
| Maintenance records: date, type, cost (BDT), vendor, odometer, notes; edit, ADMIN delete | FR-M01 | Done |
| Maintenance history per vehicle, newest first, paged, total cost over all records | FR-M02 | Done |
| Vehicle status ACTIVE / INACTIVE / MAINTENANCE via `PATCH /status` (ADMIN) | FR-V07 | Done |
| Vehicle search: number (raw or normalized), brand, model, current driver name — on the server | FR-V08 | Done |
| Server-side status/type filters, sorting (number, year, created), paging (default 20, max 100), totals | FR-V09 | Done |
| Driver CRUD with unique phone and licence number (normalized; service + DB constraints) | FR-DR01 | Done |
| Driver–vehicle assignment: one current driver per vehicle and vehicle per driver, history kept | FR-DR02 | Done |
| Dashboard: vehicles by status, documents expired/expiring, maintenance count + cost this month | FR-DB01 | Done |
| Audit fields createdAt / updatedAt / createdBy on every main record | FR-X01 | Done (users, vehicles, drivers, assignments, documents, stored files, maintenance) |
| Driver licence expiry on the dashboard (same BR-08 rule) | FR-X02 | Done |
| Login throttling: 5 failures per e-mail / 20 per IP in 15 min → 429 + `Retry-After` for 15 min | v2.1, ADR 0022 | Done |
| Optimistic locking: `version` in responses, required in PUT, 409 `stale-version` | v2.1, ADR 0023 | Done |
| ADMIN user management: list/search/filter, create, edit role/enabled, reset password; guards self-modification and last ADMIN | v2.1, ADR 0024 | Done |
| Change own password (any role); disabled / demoted / reset users affected on their next request | v2.1, ADR 0024 | Done |
| Security review fixes: IP key not client-chosen, no throttle overshoot under parallel requests, locks never evicted, log-safe keys, millisecond token cut-off, tokens revoked on disable, wrong current passwords throttled | v2.1, ADRs 0022 / 0024 (Amendments) | Done |

| Business rule | Where |
|---|---|
| BR-05 only ACTIVE vehicles and drivers can be assigned; assigning closes the previous assignment | `AssignmentService.assign` (one `@Transactional` method, close flushed before insert) |
| BR-06 one current driver per vehicle / vehicle per driver | `AssignmentService` (409 `driver-already-assigned`) + partial unique indexes mapped to the same 409 |
| BR-07 expiry ≥ issue, PDF/JPG/PNG, ≤ 5 MB | `@ExpiryOnOrAfterIssue` on the upload DTO (400 field error on `expiryDate`) + `DocumentService` + DB CHECK `ck_vehicle_documents_dates`; `FileValidator` (extension + content type + magic bytes) and multipart limits for the file |
| BR-08 expiry status in Asia/Dhaka | `ExpiryCalculator` (injected `Clock`) |
| BR-09 cost ≥ 0 (2 decimals) | DTO validation (`@DecimalMin`, `@Digits`) + `MaintenanceService` + DB CHECK `ck_maintenance_cost` |
| BR-09 service date not in the future | DTO `@PastOrPresent` (Bean Validation uses the injected Asia/Dhaka `Clock`, `ValidationConfig`) + `MaintenanceService` (same Clock). No DB CHECK: `CURRENT_DATE` would use the database server's time zone, not Asia/Dhaka |
| BR-10 vehicle with history cannot be deleted | `VehicleService.delete` asks every `VehicleUsage` (documents, maintenance, assignments each check their own table) → 409 `vehicle-has-history`; `ON DELETE RESTRICT` as the safety net |

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
  Infrastructure failures are not reported as bugs: an unreachable database or exhausted connection pool and a
  failing file storage return **503** (`internal`, "Service unavailable" / "Storage unavailable") with a one-line
  WARN log instead of a 500 with a stack trace.
- **Row locks for vehicle writes.** `PUT /vehicles/{id}`, the status PATCH and driver assignment load the vehicle
  with `SELECT ... FOR UPDATE`, so a full-row update can never write back a stale status (BR-05).
- **Gzip and HTTP timeouts.** JSON responses of 2 KB or more are gzip-compressed (`server.compression`); outgoing
  HTTP calls (Supabase storage) have a 5 s connect and 30 s read timeout, so a hanging storage cannot hold a
  pooled DB connection forever.
- **Injected `Clock` (Asia/Dhaka).** Used for JWT timestamps, audit timestamps and the "next year" bound of the
  manufacture year, so date logic is deterministic in tests — the base for v2.0 expiry rules (BR-08).
- **Stateless JWT (HS256, 60 min)**, BCrypt password hashes, `@PreAuthorize("hasRole('ADMIN')")` on delete. The
  first ADMIN comes from environment variables at start-up, never from a migration.
- **Token storage in the browser (XSS trade-off).** The Flutter Web client keeps the JWT in browser storage, which
  any script running on the page could read. Accepted for this course because tokens are short-lived (60 min),
  there is no third-party script on the page and there is no refresh token. A production system would use an
  HttpOnly, SameSite cookie with CSRF protection, or a backend-for-frontend.
- **BR-10: hard delete only without history (no soft delete).** A vehicle with any document, maintenance record or
  driver assignment (open or closed) returns 409 `vehicle-has-history` and the message suggests setting it INACTIVE.
  The same applies to drivers with assignment history (409 `driver-has-history`). Soft delete was rejected because
  every list, count and unique check would need a "deleted" filter; INACTIVE already expresses "no longer in use".
- **INACTIVE closes the current assignment.** Only ACTIVE vehicles and drivers may have a current assignment (BR-05),
  so `PATCH /vehicles/{id}/status` → INACTIVE and a driver update to INACTIVE close the open assignment in the same
  transaction. MAINTENANCE keeps the driver (the car comes back to the same person).
- **Document files in the database by default, Supabase optional.** Render's disk is not persistent, so files are
  never written to it. The default `StorageService` (`DatabaseStorageService`) keeps the bytes in `stored_files`
  (BYTEA) in the same transaction as the metadata — simple, consistent and backed up with the database; at ≤ 5 MB per
  file this is fine for a fleet of this size. The free Render PostgreSQL is limited to 1 GB (about 200 files at the
  5 MB maximum), so use `APP_STORAGE_PROVIDER=supabase` for real use. That setting switches to a private Supabase
  Storage bucket through its REST API (`SupabaseStorageService`) without touching any other code. Files are always
  served through the API with the user's JWT; the storage key is a random UUID, never the user's file name.
- **Upload checks.** Extension, declared content type and the file's magic bytes (`%PDF`, `FF D8 FF`, `89 50 4E 47`)
  must agree, so a renamed executable is rejected. Downloads send `X-Content-Type-Options: nosniff` and a
  `Content-Disposition` with the sanitized original name (`inline`, or `attachment` with `download=true`); CORS
  exposes `Content-Disposition` to the Flutter client.
- **Dates and "today".** Expiry status (documents, licences), "service date not in the future" and "this month" on the
  dashboard all use `LocalDate.now(clock)` in Asia/Dhaka, so "expires today" switches at midnight in Dhaka.
- **Dashboard from COUNT/SUM queries.** One read-only REPEATABLE READ transaction (one snapshot, so the numbers agree
  with each other) with a GROUP BY for vehicle statuses and COUNT/SUM queries for everything else; only the two
  top-10 lists load rows. Licence counts include all drivers.
- **No N+1 for current drivers.** A vehicle page loads the current drivers of all its vehicles with one extra
  query (`... where a.vehicle.id in :ids and a.unassignedAt is null`), and the same for drivers' current vehicles.
- **Demo data is real data.** `dev/DemoDataSeeder` inserts the demo fleet into the database (local profile only,
  idempotent); the frontend has no mock lists.

## Known limitations / next steps

- No refresh tokens and no logout endpoint: "log out" only discards the token on the client. Disabling a user,
  demoting them, a password reset or an own password change do revoke older tokens on the next request (ADR 0024);
  only a token issued in the very same millisecond as the change survives it.
- Login throttling (ADR 0022) keeps its state in memory per instance: a restart or redeploy unlocks everything, and
  with more than one instance each would count separately (follow-up: a shared store). Anyone who knows an e-mail can
  keep that account locked (5 wrong passwords every 15 minutes); users behind one NAT address share the 20-failure
  IP budget.
- The per-IP key trusts the right-most `X-Forwarded-For` entry added by a proxy in the internal ranges; a different
  hosting topology (a CDN with a public address in front) needs `SERVER_TOMCAT_REMOTEIP_*` adjusted.
- Documents cannot be edited (delete and upload again); there is no virus scanning beyond the type checks.
- With `supabase` storage the upload and the metadata insert are not one transaction; a failed insert removes the
  uploaded object again, but a crash between the two could leave an orphaned object in the bucket.
- No e-mail/SMS reminders for expiring documents (out of scope, guideline 3.2).
- The embedded-PostgreSQL launcher (`spring-boot:test-run`) is for development only; its data is temporary.

## Deploy to Render

The repository contains a Render Blueprint (`render.yaml`) and a multi-stage `Dockerfile`.

1. Create the database on **Neon** (free tier, does not expire; ADR 0018 D11): a project in the same region as
   the Render service, no add-ons. Push the repository to GitHub, then in Render choose **New → Blueprint** and
   select the repository; it creates the Docker web service `stark-vms-backend` (health check `/actuator/health`,
   `JWT_SECRET` generated). Without Blueprints: create a Web Service with runtime *Docker* from this repository.
2. **Convert the database URL.** In Neon open **Connect**, switch **Connection pooling off** (Flyway needs a
   session-level connection, so use the direct host, not the `-pooler` one) and copy the string. It looks like
   `postgresql://neondb_owner:PASSWORD@ep-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require`.
   Spring needs the JDBC form without the credentials:
   - `DB_URL` = `jdbc:postgresql://ep-xxxx.ap-southeast-1.aws.neon.tech:5432/neondb?sslmode=require`
   - `DB_USERNAME` = `neondb_owner`, `DB_PASSWORD` = the password from the string.
   - All three are `sync: false` in `render.yaml`, so the Blueprint asks for them and never overwrites them.
   - Render PostgreSQL works the same way, with two traps: Render appends a suffix to the database name, so read
     it from the dashboard, and its free instance expires after about 30 days. ADR 0016 has the details.
3. Set the remaining variables in the dashboard: `CORS_ALLOWED_ORIGINS=https://<your-site>.netlify.app (here: https://stark-vms-avishek.netlify.app)`,
   `APP_ADMIN_EMAIL`, `APP_ADMIN_PASSWORD` (and optionally the `APP_STAFF_*` values). If you do not use the
   Blueprint, also set `JWT_SECRET` (≥ 32 bytes). Do **not** set `SPRING_PROFILES_ACTIVE=local` in production.
4. Deploy. Render injects `PORT`; the app binds to it (`server.port=${PORT:8080}`). Flyway creates the schema on
   first start and the bootstrap admin is created.
5. Smoke-test: `GET /actuator/health` → `{"status":"UP"}`, log in through Swagger UI or the Postman collection,
   list and create a vehicle, try a duplicate (409), open `GET /api/v1/dashboard/summary`, upload a small PDF and
   view it.
   Document files are stored in the Render PostgreSQL database by default (`APP_STORAGE_PROVIDER=database`); to use
   Supabase Storage instead, create a private bucket and set `APP_STORAGE_PROVIDER=supabase`, `SUPABASE_URL`,
   `SUPABASE_SERVICE_KEY` and `SUPABASE_BUCKET`.
6. After the frontend is on Netlify, make sure its URL is in `CORS_ALLOWED_ORIGINS` and redeploy.

Build and run the image locally (needs a reachable PostgreSQL):

```bash
docker build -t stark-vms-backend .
docker run -p 8080:8080 -e DB_URL=jdbc:postgresql://host.docker.internal:5432/vms \
  -e DB_USERNAME=postgres -e DB_PASSWORD=postgres -e JWT_SECRET="$(openssl rand -base64 48)" \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5000 -e APP_ADMIN_EMAIL=admin@stark.local \
  -e APP_ADMIN_PASSWORD=Admin@12345 stark-vms-backend
```
