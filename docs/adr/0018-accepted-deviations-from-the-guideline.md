# 0018. Accepted deviations from the guideline

- **Status:** Accepted
- **Date:** 2026-11-07
- **Amended:** 2026-11-10 (D11, production database moved to Neon)
- **Recorded:** retroactively, after implementation (each deviation was introduced when the feature named in its
  row was built in v1.0 or v2.0; the BR-09 DTO check was added on 2026-11-07)

## Context

The project guideline (*Stark_Group_VMS_Project_Guideline*, v1.0 of 4 November 2026) prescribes the stack, the
schema, the endpoints and where each business rule is enforced. In some places the implementation does something
different, either because the guideline's suggestion does not work as written, or because the course
constraints (no Docker on the development machine, free tiers, zero extra accounts) make another option better.
A deviation that is not written down looks like a mistake. This ADR lists every known deviation with its reason,
so a reviewer can judge each one.

## Decision

The following deviations are accepted. Each one names the guideline text, what the code does, and why.

### D1. BR-09 "service date not in the future" has no database CHECK

- **Guideline** (section 12): BR-09 "Maintenance cost ≥ 0; service date not in the future", enforced in
  "DTO + DB CHECK".
- **Code:**
  - *Cost ≥ 0* follows the guideline: DTO `@DecimalMin("0.00")` and `@Digits(10, 2)` in
    `BE/maintenance/dto/MaintenanceRequest.java`, a check in `MaintenanceService`, and the DB CHECK
    `ck_maintenance_cost CHECK (cost >= 0)` in `MIG/V6__create_maintenance.sql`.
  - *Service date not in the future* is enforced twice, both against the injected Asia/Dhaka `Clock`:
    - the DTO has `@PastOrPresent`, and `BE/config/ValidationConfig.java` registers a
      `ValidationConfigurationCustomizer` that sets Bean Validation's `ClockProvider` to that clock;
    - `MaintenanceService` checks `serviceDate.isAfter(today)` again ("Service date cannot be in the future.",
      field `serviceDate`).
  - There is **no** DB CHECK on `service_date`.
- **Why:**
  - A `CHECK (service_date <= CURRENT_DATE)` would compare with the date in the *database session's* time zone,
    not Asia/Dhaka. With a UTC database the check would reject a valid record entered between 00:00 and 06:00
    Dhaka time, which is when the Dhaka date is already "today" but the UTC date is still yesterday.
  - The guideline's own rule BR-08 says "today" is in Asia/Dhaka, and section 15 asks for deterministic date
    tests with a fixed clock. A database CHECK cannot use the injected clock, so it could not be tested that way.
  - A `CHECK` that uses `CURRENT_DATE` is also evaluated only on INSERT/UPDATE, so it is not a true invariant.
- **Evidence:** `MaintenanceRequestValidationTest` (fixed clock 2026-11-17T19:30Z: tomorrow in Dhaka → 400; today
  in Dhaka, still yesterday in UTC → 201), `MaintenanceServiceTest`, `MaintenanceApiTest` (future date 400, DB CHECK
  on cost). The README business-rule table has the same rows (`stark-vms-backend/README.md`, BR-09).

### D2. `GET /vehicles/{id}/documents` returns a bare JSON array, not a page

- **Guideline** 11.1: "List endpoints accept page, size, sort and filter parameters and return the page wrapper".
  The 11.3 catalogue lists `GET /vehicles/{id}/documents` as "List / upload" without saying "paged". It says
  "paged" explicitly only for the maintenance history.
- **Code:** `docs/API_CONTRACT.md` §6.5 specifies "JSON **array** (not paged) of DocumentResponse, ordered by
  `expiryDate` asc". `DocumentController.list` returns `List<DocumentResponse>` from
  `VehicleDocumentRepository.findByVehicleIdOrderByExpiryDateAscIdAsc`. The query uses the index
  `idx_vehicle_documents_vehicle (vehicle_id, expiry_date)`.
- **Why:**
  - The list belongs to one vehicle and is small in practice. A vehicle has a handful of current papers (six
    document types), plus renewals over the years.
  - The Documents tab shows them all at once, sorted by expiry, so that the most urgent paper is first.
- **Honest limit:** the size is *not* bounded by the schema. There is no unique `(vehicle_id, document_type)`, and
  renewals accumulate (for example a yearly tax token), so the list grows by a few rows per vehicle per year.
- **Fleet-wide document lists are paged:** `GET /documents/expiring` returns a `PageResponse`.

### D3. Document bytes are stored in PostgreSQL (`BYTEA`) by default, not in object storage

- **Guideline:**
  - section 8: "Object storage with a free tier (Cloudinary, Supabase Storage or S3-compatible)";
  - risk table (21): "Object storage from the start behind a StorageService interface";
  - 18.1: "Object storage dev folder / production folder".
- **Code:**
  - The `StorageService` interface exists from the start, as the risk table asks.
  - The default implementation is `DatabaseStorageService` (table `stored_files`), and `SupabaseStorageService`
    is selected with `APP_STORAGE_PROVIDER=supabase`.
  - `APP_STORAGE_KEY_PREFIX` (`render.yaml`: `prod/`) gives the dev/prod folders in a shared bucket.
- **Why:** the app runs with zero extra accounts, locally, in CI and on Render. Files in the managed database
  survive redeploys, which is the actual risk the guideline names ("Uploaded files lost on Render").
- **Cost:** the database grows with the files (1 GB on the free plan, about 200 files at 5 MB). Use `supabase`
  for real use. Details in [0013](0013-document-file-storage-behind-storageservice.md).

### D4. Embedded PostgreSQL (zonky) instead of Testcontainers

- **Guideline:**
  - section 15: API tests with "Testcontainers PostgreSQL (or a test database)";
  - section 8: "Testcontainers (optional)";
  - risk table: "PostgreSQL everywhere; Testcontainers for API tests".
- **Code:** `io.zonky.test:embedded-database-spring-test` starts a real PostgreSQL 17.5 binary for every
  `*ApiTest` class and runs the Flyway migrations. `./mvnw spring-boot:test-run` uses the same binary for a local
  backend.
- **Why:** Testcontainers needs a Docker daemon, and the Windows development machine has none. zonky gives the
  same thing the guideline wants: real PostgreSQL SQL, partial unique indexes, CHECKs and FKs. It needs no
  Docker in CI either. This uses the guideline's "(or a test database)" option. Details in
  [0006](0006-postgresql-everywhere-flyway-and-validate.md).

### D5. User accounts cannot be managed in the application

- **Guideline** section 4: ADMIN can "manage user accounts". Section 11.3 lists no user endpoint.
- **Code:** accounts come only from `APP_ADMIN_*` / `APP_STAFF_*` at start-up.
- A known gap; see [0017](0017-user-account-management-scope.md).

### D6. Driver licence uniqueness is on a normalized key, not on `license_number`

- **Guideline** 10.1: `drivers` … "license_number UNIQUE".
- **Code** (`MIG/V3__create_drivers.sql`):
  - `license_number` is stored as entered, and `license_key` (normalized by `BE/driver/LicenseNumber.java`) has
    `uk_drivers_license_key UNIQUE`;
  - the phone is stored normalized with `uk_drivers_phone UNIQUE (phone)`.
- **Why:** this is the same reasoning as BR-01 for vehicle numbers ([0009](0009-vehicle-number-normalization-and-duplicate-check.md)).
  `DL-123` and `dl 123` are the same licence, and a plain UNIQUE on the raw text would let both in. The rule is
  stricter than the guideline, not weaker.

### D7. Endpoints and status codes beyond the catalogue (additive)

- **Guideline** 11.3 is the endpoint catalogue, and 11.1 is the status table.
- **Code adds:**
  - `GET /documents/{docId}`, `GET /maintenance/{recordId}`, `GET /drivers/{id}/assignments`;
  - the `available` filter on `GET /drivers`;
  - 503 for an unreachable database or storage.
- **Why:** the screens need them (document detail, editing one maintenance record, driver history, the assign
  dialog). All of them are additive, listed in `docs/API_CONTRACT.md` and documented in Swagger.

### D8. Migration numbering differs from the guideline's example

- **Guideline** 10.3 shows an excerpt named `V5__create_assignments.sql`.
- **Code:** the assignments table is `V4__create_assignments.sql`, created right after `V3__create_drivers.sql`
  because it references `drivers`. Documents are V5 and maintenance is V6 (contract §6.9).
- **Why:** the order follows the FK dependencies. The guideline's file name is an excerpt, not a requirement.

### D9. Logs are key=value text, not structured (JSON)

- **Guideline** section 6, Observability: "Structured logs with request path and status".
- **Code:** `RequestLoggingFilter` writes `method=… path=… status=… durationMs=…` on one line per request, with
  Logback's default text layout. There is no JSON encoder and no request id.
- **Why:** this was enough for the Render log viewer, and it adds no dependency. It is recorded as a limitation
  (NFR-MAINT-03) in [0015](0015-non-functional-requirements-strategy.md), with JSON logs plus a request id as the
  follow-up.

### D10. The end-to-end journey is not run in CI

- **Guideline** section 15 lists end-to-end journeys (Flutter `integration_test`), and section 18.2 lists the CI
  steps, which do not include them.
- **Code:** `stark-vms-frontend/integration_test/app_journey_test.dart` runs against a live backend with
  ChromeDriver (`flutter drive`). It was last run on 2026-11-07, and all tests passed. CI runs analyze, the
  unit and widget tests and the build only.
- **Why:** the journey needs a running backend with a database and a matching ChromeDriver. That is a heavier CI
  setup than the course needs. See [0020](0020-test-strategy-and-ci.md).

### D11. The production database is Neon, not Render PostgreSQL

- **Guideline** section 11 names "Render (backend + DB)" as the deployment target, and section 18.3 starts with
  "Create a PostgreSQL instance on Render".
- **Code:** unchanged. The backend still runs on Render as a Docker web service; only `DB_URL`, `DB_USERNAME`
  and `DB_PASSWORD` point at a Neon project (`stark-vms`, PostgreSQL 18, AWS Singapore, the same region as the
  Render service). `render.yaml` no longer declares a database and takes all three values as `sync: false`.
- **Why:** Render's free PostgreSQL expires about 30 days after it is created. The submission is reviewed after
  the deadline, and the live link is meant to keep working afterwards, so a database that disappears in early
  December would leave a site that loads but cannot sign anyone in. Neon's free tier does not expire, and its
  compute resumes on the first connection after going idle, so nobody has to wake it by hand. Supabase was
  considered and rejected for the same reason: its free projects pause after a week without activity and must be
  restored from the dashboard.
- **What stayed the same:** it is still PostgreSQL in every environment (section 8, ADR
  [0006](0006-postgresql-everywhere-flyway-and-validate.md)); Flyway created the whole schema on Neon from V1–V8
  on the first start; the partial unique indexes behave identically; no code, migration or test changed.
- **Moved on 2026-11-10:** the Render service was switched to Neon and the demo fleet was entered again through
  the API as the ADMIN user (ADR [0021](0021-demo-data-seeded-into-the-real-database.md)). Checked afterwards:
  health UP, both demo logins, 26 vehicles, the dashboard figures, and a document download.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Follow the guideline literally everywhere | D1 would reject valid records for six hours a day; D4 would make the tests impossible on the development machine; D6 would let duplicate licences in. |
| Silently deviate | A reviewer would read a deviation as a bug. The guideline itself asks to "document" choices (BR-10). |
| Change the guideline document | It is the course's document, not ours. |

## Consequences

**Positive**
- Each deviation has a reason and evidence, and most have a follow-up in another ADR.

**Negative / accepted trade-offs**
- The database does not protect against a future `service_date` written by something other than the API (D1).
- One unpaged list (D2) must stay small, which is not enforced.

**Follow-ups**
- D1: if a database-level guard is wanted, a trigger that compares with `(now() AT TIME ZONE 'Asia/Dhaka')::date`
  (one zone constant in SQL, documented as a second copy of `ClockConfig.BUSINESS_ZONE`).
- D2: if a vehicle's documents ever exceed a few dozen, page the endpoint in a new API version, or add a
  `current` filter that shows the newest document per type.
- D3, D5, D9, D10: see 0013, 0017, 0015 and 0020.
- D11: see [0016](0016-deployment-render-and-netlify.md), section "Database moved to Neon".
- A new deviation gets a row here, through a new ADR before implementation ([0001](0001-record-architecture-decisions.md)).

## Related

- Guideline: sections 4, 6, 8, 10.1, 10.3, 11, 11.1, 11.3, 12 (BR-08, BR-09), 15, 18.1, 18.2, 18.3, 21.
- ADRs: [0006](0006-postgresql-everywhere-flyway-and-validate.md), [0009](0009-vehicle-number-normalization-and-duplicate-check.md),
  [0011](0011-time-and-expiry-with-injected-clock.md), [0013](0013-document-file-storage-behind-storageservice.md),
  [0015](0015-non-functional-requirements-strategy.md), [0017](0017-user-account-management-scope.md),
  [0020](0020-test-strategy-and-ci.md).
- Files: `BE/maintenance/dto/MaintenanceRequest.java`, `BE/maintenance/MaintenanceService.java`,
  `BE/config/ValidationConfig.java`, `MIG/V3__create_drivers.sql`, `MIG/V5__create_documents.sql`,
  `MIG/V6__create_maintenance.sql`, `BE/document/DocumentController.java`, `BE/document/storage/`,
  `BE/common/web/RequestLoggingFilter.java`, `stark-vms-backend/pom.xml`, `docs/API_CONTRACT.md` §3, §6.5, §6.9.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`.
