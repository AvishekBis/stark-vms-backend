# 0002. Bounded contexts and package-by-feature

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built with
  `auth` and `vehicle`; the v2.0 contexts were added under the same rule when v2.0 was built)

## Context

The system covers several business areas with different rules and lifecycles: login and roles, the vehicle
register, drivers, driver-vehicle assignments, vehicle documents with files, maintenance records and a summary
dashboard. Guideline section 9 says "Organise code by feature, not by technical type — everything about vehicles
lives in one place", and the maintainability NFR (section 6) says "Package-by-feature; no business logic in
controllers or widgets". The project is built by one developer, deployed as one Render web service with one
PostgreSQL database (section 18), and v2.0 had to extend v1.0 without breaking it.

## Decision

Each business area is a **bounded context**, implemented as one backend package under `com.starkgroup.vms` and
one Flutter feature folder under `lib/features/`. Both apps are single deployables (a modular monolith and a
single SPA). Cross-cutting code lives in a small shared kernel. Who owns which table and how contexts may use
each other is decided in [0003](0003-data-ownership-and-cross-context-contracts.md).

### Backend contexts (`BE/`)

| Context (package) | Responsibility | Owns | Since |
|---|---|---|---|
| `auth` | Login, JWT issue/verify, roles, start-up accounts | `User`, `Role`, `JwtService`, `JwtAuthenticationFilter`, `AdminBootstrap`, 401/403 writers | v1.0 |
| `vehicle` | Vehicle register, number normalization, status, search/filter/sort, BR-10 delete guard | `Vehicle`, `VehicleNumber`, `VehicleSpecifications`, `VehicleSortField`, `VehicleUsage` port | v1.0 (extended in v2.0) |
| `driver` | Driver register, phone and licence normalization, licence expiry | `Driver`, `DriverPhone`, `LicenseNumber`, `DriverSpecifications` | v2.0 |
| `assignment` | Who drives which vehicle now, and the history (BR-05, BR-06) | `VehicleDriverAssignment`, `AssignmentService` | v2.0 |
| `document` | Vehicle documents, file validation, expiry (FR-D01, FR-D02, BR-07) | `VehicleDocument`, `FileValidator`; sub-package `document.storage` owns file bytes (`StorageService`, `StoredFile`) | v2.0 |
| `maintenance` | Maintenance records and history with total cost (FR-M01, FR-M02, BR-09) | `MaintenanceRecord` | v2.0 |
| `dashboard` | Read model: all summary numbers in one call (FR-DB01, FR-X02) | no entity, no table | v2.0 |
| `dev` | Local demo data (`DemoDataSeeder`, `DemoFiles`), only with `app.seed-demo-data=true` | no table; not a business context | v1.0 (extended in v2.0) |

Every context has the same internal layout: `*Controller` → `*Service` → `*Repository` (Spring Data JPA) →
entity, plus a hand-written `*Mapper` and Java-record DTOs in `dto/` ([0005](0005-layered-architecture-and-solid-conventions.md)).
A context that owns unique constraints registers them as `UniqueConstraintRule` beans in its own
`*ConstraintConfig` (`BE/vehicle/VehicleConstraintConfig.java`, `BE/driver/DriverConstraintConfig.java`,
`BE/assignment/AssignmentConstraintConfig.java`).

**Shared kernel (backend):**
- `common/` — `dto/PageResponse`, `util/PageRequests`, `util/LikePatterns`, `util/TextValues`,
  `exception/*` (Problem Details, `ApiErrorType`, `UniqueConstraintRule`, the shared exception types),
  `expiry/*` (BR-08 calculator shared by documents and driver licences), `persistence/AuditableEntity`,
  `validation/@ManufactureYear`, `web/*` (request log filter, `/error` controller, Problem Details writer).
- `config/` — cross-cutting beans: `SecurityConfig`, `CorsConfig`/`CorsProperties`, `ClockConfig`,
  `JpaAuditingConfig`, `OpenApiConfig`, `ValidationConfig`.

The shared kernel must not know any business context. Two exceptions exist today and are listed in 0003:
`common/exception/GlobalExceptionHandler.java` imports `document.storage.StorageException`, and `config/` wires
`auth` classes into the security chain and the JPA auditor.

### Frontend features (`FE/features/`)

| Feature | Responsibility | Screens / entry points | Since |
|---|---|---|---|
| `auth` | Session lifecycle, login, current user | `login_screen.dart`, `auth_controller.dart` | v1.0 |
| `vehicles` | Vehicle list, form, detail with tabs, status dialog, delete flow | `/vehicles`, `/vehicles/new`, `/vehicles/:id`, `/vehicles/:id/edit` | v1.0 (extended in v2.0) |
| `drivers` | Driver list, form, detail | `/drivers`, `/drivers/new`, `/drivers/:id`, `/drivers/:id/edit` | v2.0 |
| `assignments` | Assign dialog and assignment history view (no route of its own) | embedded in vehicle and driver detail | v2.0 |
| `documents` | Documents tab, upload dialog, expiring-documents screen | vehicle tab, `/documents/expiring` | v2.0 |
| `maintenance` | Maintenance tab and form dialog | vehicle tab | v2.0 |
| `dashboard` | Summary cards and lists from `GET /dashboard/summary` | `/dashboard` | v1.0 (rebuilt on the summary endpoint in v2.0) |

Each feature has `data/` (models with `fromJson`, an `abstract interface class *Repository`, an
`Api*Repository` and its provider) and `presentation/` (providers, controllers, screens, widgets).

**Shared kernel (frontend):**
- `core/` — `config/` (`AppConfig`), `network/` (`ApiClient`, `ApiException`, `PageResponse`, `FormErrors`,
  `ResponseParsing`, `SortDirection`, `SlowRequestMonitor`, session signals), `platform/` (file opener),
  `router/` (the only place that knows every screen), `storage/`, `theme/`, `utils/` (`ChangeTracker` plus the
  shared vehicle and driver change counters, `Clock`, `dates`, `enum_parsing`, validators, formatters).
- `shared/` — `models/expiry_status.dart` and generic widgets (`AppShell`, `StatusBadge`, `ExpiryBadge`,
  `SortMenu`, `RowActions`, `DataTableFrame`, `PaginationBar`, state views, dialogs).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Package-by-layer (`controllers/`, `services/`, `repositories/`) | Explicitly against guideline section 9; a change to one feature touches every folder. |
| Separate Maven modules or microservices per context | One developer, one free Render instance, one database. Several rules span contexts inside one transaction (assigning closes the previous assignment and checks vehicle and driver status, BR-05; INACTIVE closes the assignment), which would need distributed transactions or sagas. |
| Spring Modulith (verified module boundaries, events) | Not evaluated when v1.0 was built; an extra dependency the guideline does not list. Kept as a follow-up for enforcing the rules of 0003. |
| One "fleet" context for vehicles, drivers and assignments | The three have separate tables, endpoints and screens, and drivers have their own lifecycle (status, licence expiry). Keeping them apart made v2.0 additive. |

## Consequences

**Positive**
- v2.0 added `driver`, `assignment`, `document`, `maintenance` and `dashboard` as new packages and features; the
  v1.0 `auth` package is unchanged and every v1.0 test still runs.
- A reader finds everything about one area in one folder, on both sides, with matching names.

**Negative / accepted trade-offs**
- Boundaries are a convention only. Almost every class is `public`, and nothing in the build fails when a
  context reaches into another (a compile-time check does not exist).
- `vehicle`, `assignment` and `driver` form package-level cycles (see 0003, finding SOLID-BE-03). On the
  frontend `vehicles ↔ assignments` and `vehicles ↔ documents` are import cycles.
- `dashboard` and `dev` depend on almost every context by design.

**Follow-ups**
- Add an architecture test (ArchUnit, or Spring Modulith's `ApplicationModules.verify()`) that encodes the
  allowed dependency directions of 0003.
- Break the `vehicle ↔ assignment ↔ driver` cycle (narrow ports owned by `vehicle` and `driver`, implemented in
  `assignment`, the same pattern as `VehicleUsage`).

## Related

- Guideline: sections 6 (Maintainability), 7, 9.1, 9.2.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0005](0005-layered-architecture-and-solid-conventions.md).
- Files: `BE/*/`, `FE/features/*/`, `FE/core/`, `FE/shared/`, `stark-vms-backend/README.md` (Architecture),
  `stark-vms-frontend/README.md` (Architecture).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
