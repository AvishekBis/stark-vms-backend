# 0002. Bounded contexts and package-by-feature

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built)

## Context

v1.0 covers two business areas, login with roles and the vehicle register. Assignment 2 was known to follow with
drivers, assignments, documents, maintenance and a dashboard (guideline section 5.2). Guideline section 9 says
"Organise code by feature, not by technical type — everything about vehicles lives in one place". The
maintainability NFR (section 6) says "Package-by-feature; no business logic in controllers or widgets". The
project is built by one developer and deployed as one Render web service with one PostgreSQL database
(section 18).

## Decision

Each business area is a **bounded context**, implemented as one backend package under `com.starkgroup.vms` and
one Flutter feature folder under `lib/features/`. Both apps are single deployables: a modular monolith and a
single SPA. Cross-cutting code lives in a small shared kernel. Who owns which table, and how contexts may use
each other, is decided in [0003](0003-data-ownership-and-cross-context-contracts.md).

### Backend contexts (`BE/`)

| Context (package) | Responsibility | Owns |
|---|---|---|
| `auth` | Login, JWT issue/verify, roles, start-up accounts | `User`, `Role`, `AuthService`, `JwtService`, `JwtAuthenticationFilter`, `AdminBootstrap`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`, `AuthenticatedUser` |
| `vehicle` | Vehicle register, number normalization, search by number, sort, paging | `Vehicle`, `VehicleNumber`, `VehicleSpecifications`, `VehicleSortField`, `VehicleConstraintConfig`, enums `VehicleStatus`, `VehicleType`, `FuelType` |
| `dev` | Local demo data (`DemoDataSeeder`), only with `app.seed-demo-data=true` | no table; not a business context |

Every context has the same internal layout: `*Controller` → `*Service` → `*Repository` (Spring Data JPA) →
entity, plus a hand-written `*Mapper` (vehicle) and Java-record DTOs in `dto/`
([0005](0005-layered-architecture-and-solid-conventions.md)). A context that owns a unique constraint registers it
as a `UniqueConstraintRule` bean in its own `*ConstraintConfig` (`BE/vehicle/VehicleConstraintConfig.java`).

**Shared kernel (backend)**
- `common/`:
  - `dto/PageResponse`;
  - `util/PageRequests` and `util/LikePatterns`;
  - `exception/*`: Problem Details, `ApiErrorType`, `UniqueConstraintRule`, the shared exception types;
  - `persistence/AuditableEntity`;
  - `validation/@ManufactureYear`;
  - `web/*`: the request log filter, the `/error` controller, the Problem Details writer.
- `config/`: `SecurityConfig`, `CorsConfig`/`CorsProperties`, `ClockConfig`, `JpaAuditingConfig`,
  `OpenApiConfig`.
- `common/` imports no business context. `config/` wires `auth` classes into the security chain and the JPA
  auditor.

### Frontend features (`FE/features/`)

| Feature | Responsibility | Screens / routes |
|---|---|---|
| `auth` | Session lifecycle, login, current user | `login_screen.dart` (`/login`), `auth_controller.dart` |
| `vehicles` | Vehicle list, form, detail, delete flow | `/vehicles`, `/vehicles/new`, `/vehicles/:id`, `/vehicles/:id/edit` |
| `dashboard` | Total vehicles and the newest vehicles, derived from `GET /vehicles` (only `presentation/`) | `/dashboard` |

`auth` and `vehicles` have `data/` and `presentation/`:
- `data/` holds the models with `fromJson`, an `abstract interface class *Repository`, an `Api*Repository` and
  its provider;
- `presentation/` holds the providers, controllers, screens and widgets.

**Shared kernel (frontend)**
- `core/`:
  - `config/` (`AppConfig`);
  - `network/` (`ApiClient`, `ApiException`, `PageResponse`, `SlowRequestMonitor`, session signals);
  - `router/` (the only place that knows every screen);
  - `storage/`, `theme/`;
  - `utils/` (`Clock`, validators, formatters, debouncer).
- `shared/widgets/` — `AppShell`, `StatusBadge`, state views, `ConfirmDialog`, `PaginationBar`,
  `ConnectingBanner`, `SplashScreen`, `AppFeedback`, `MissingConfigurationApp`.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Package-by-layer (`controllers/`, `services/`, `repositories/`) | Explicitly against guideline section 9; every v2.0 feature would touch every folder. |
| Separate Maven modules or microservices per context | One developer, one free Render instance and one database. v2.0 rules span contexts inside one transaction, for example "assigning closes the previous assignment" (BR-05). |
| Spring Modulith (verified module boundaries) | Not evaluated when v1.0 was built; an extra dependency the guideline does not list. |
| One flat package for a small v1.0 | Cheap now, but v2.0 adds five areas; moving files later would rewrite every import and the test layout. |

## Consequences

**Positive**
- v2.0 could add `driver`, `assignment`, `document`, `maintenance` and `dashboard` as new packages and features
  without touching `auth`. This is visible in Assignment 2, where `auth` is byte-identical.
- A reader finds everything about one area in one folder, on both sides, with matching names.

**Negative / accepted trade-offs**
- Boundaries are a convention only. Almost every class is `public`, and nothing in the build fails when a context
  reaches into another.
- The v1.0 dashboard has no `data/` layer of its own. It reads the vehicles feature's repository (see 0003).

**Follow-ups**
- An architecture test (ArchUnit) that encodes the dependency rules of 0003 before the context count grows.

## Related

- Guideline: sections 6 (Maintainability), 7, 9.1, 9.2.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0005](0005-layered-architecture-and-solid-conventions.md).
- Files: `BE/auth/`, `BE/vehicle/`, `BE/common/`, `BE/config/`, `BE/dev/`, `FE/features/*/`, `FE/core/`,
  `FE/shared/`, `stark-vms-backend/README.md` (Architecture), `stark-vms-frontend/README.md` (Architecture).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
