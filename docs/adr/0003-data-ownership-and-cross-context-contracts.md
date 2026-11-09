# 0003. Data ownership and cross-context contracts

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built — DTOs at
  the edges and `docs/API_CONTRACT.md` as the contract — and extended when v2.0 added tables owned by new
  contexts; the `VehicleUsage` port was introduced in the v2.0 hardening pass of 2026-11-07)

## Context

[0002](0002-bounded-contexts-and-package-by-feature.md) splits the system into contexts, but they share one
PostgreSQL database and one JVM. Without rules, any service can read or write any table, and the contexts
become one big ball of mud. The vehicle is the centre of the model (guideline section 10: "documents,
maintenance records and driver assignments all belong to a vehicle"), so other contexts must reference it.
The frontend and backend are separate projects (two repositories, guideline section 9) and need a stable
contract. Guideline section 9 also says "JPA entities never leave the service layer. Controllers receive and
return DTOs".

## Decision

### 1. Every table has exactly one owning context

| Table | Migration | Owning context (entity) | Foreign keys |
|---|---|---|---|
| `users` | `MIG/V1__create_users.sql` | `auth` (`BE/auth/User.java`) | — |
| `vehicles` | `MIG/V2__create_vehicles.sql` | `vehicle` (`BE/vehicle/Vehicle.java`) | — |
| `drivers` | `MIG/V3__create_drivers.sql` | `driver` (`BE/driver/Driver.java`) | — |
| `vehicle_driver_assignments` | `MIG/V4__create_assignments.sql` | `assignment` (`BE/assignment/VehicleDriverAssignment.java`) | `vehicle_id` → `vehicles`, `driver_id` → `drivers`, both `ON DELETE RESTRICT` |
| `stored_files` | `MIG/V5__create_documents.sql` | `document.storage` (`BE/document/storage/StoredFile.java`) | — |
| `vehicle_documents` | `MIG/V5__create_documents.sql` | `document` (`BE/document/VehicleDocument.java`) | `vehicle_id` → `vehicles` `RESTRICT`; linked to `stored_files` only by `storage_key` (no FK, cleaned up by `DocumentService`) |
| `maintenance_records` | `MIG/V6__create_maintenance.sql` | `maintenance` (`BE/maintenance/MaintenanceRecord.java`) | `vehicle_id` → `vehicles` `RESTRICT` |

Only the owning context writes (INSERT/UPDATE/DELETE) its table, and only the owner adds migrations that change
it. All tables carry `created_at`, `updated_at`, `created_by` through `BE/common/persistence/AuditableEntity.java`.

### 2. How another context may read owned data

In order of preference:

- **(a) Owner's service.** Call a public method of the owning context's service. Example:
  `AssignmentService.currentDriversByVehicleId(...)`, used by `VehicleService`.
- **(b) Port owned by the consumer.** The context that needs an answer declares an interface, and each owning
  context implements it over its own repository. Example: `BE/vehicle/VehicleUsage.java`, implemented by
  `DocumentVehicleUsage`, `MaintenanceVehicleUsage` and `AssignmentVehicleUsage`. `VehicleService.delete` uses
  it for BR-10, so `vehicle` never reads the other tables.
- **(c) Reference to a root aggregate.** Read-only use of the `vehicle` or `driver` repository or entity, for an
  existence check, a `@ManyToOne` reference or a row lock. This is allowed only towards these two contexts,
  because every other context hangs off them.
- **(d) JPA join on another context's entity.** A JPQL or Criteria join or subquery is tolerated only when a
  filter needs it, and every occurrence is listed below.

**Forbidden:**
- native SQL against another context's tables;
- writing another context's table;
- the shared kernel (`common/`) importing a business context;
- an entity in a controller signature or a response DTO.

**Allowed directions:**
- `document`, `maintenance`, `assignment` → `vehicle`
- `assignment` → `driver`
- `dashboard` → any context (read-only)
- `dev` → any context (local profile only)
- every context → `common` / `config`
- `config` → `auth` (wiring only)

`vehicle` and `driver` call `assignment` only through `AssignmentService`. This is the known cycle, see below.

Cross-context side effects are **synchronous calls inside the caller's transaction**. There are no domain events.
For example, `AssignmentService.closeCurrentForVehicle` and `closeCurrentForDriver` are `@Transactional` and
join the caller.

### 3. The API contract is the frontend/backend contract

- `docs/API_CONTRACT.md` is the source of truth for every path, JSON shape, enum, status code and Problem Details
  `type`.
- Sections 1–5 are v1.0, and Section 6 lists the v2.0 additions ("Section 6 wins on any conflict").
- Changes are additive and backward compatible. Fields and types are never renamed or removed within `/api/v1`.
- The OpenAPI document generated from the code (`/v3/api-docs`, `BE/config/OpenApiConfig.java`) and
  `stark-vms-backend/docs/postman_collection.json` must agree with it.
- The frontend mirrors each response DTO with a `fromJson` model in `FE/features/*/data/`.

### 4. DTOs only at the edges

- Request and response DTOs are Java records in each context's `dto/`, and a hand-written `*Mapper` converts them.
- No controller imports a repository or returns an entity.
- Entities do cross context boundaries inside the service layer: `AssignmentService` returns `Driver` and
  `Vehicle` entities to `VehicleService` and `DriverService` (rule (c)).
- `DashboardSummaryResponse` embeds `document.dto.DocumentResponse` and `driver.dto.DriverResponse`. A context
  may reuse another context's *response DTO*, because DTOs are part of the published contract.

### Every existing cross-context dependency (backend, `common` imports excluded)

| From → to | Where | Kind | Follows the rule? |
|---|---|---|---|
| vehicle → assignment | `VehicleService` uses `AssignmentService` (`currentDriversByVehicleId`, `currentDriverOf`, `closeCurrentForVehicle`) | (a) | Yes, but it closes the package cycle vehicle ↔ assignment |
| vehicle → driver | `VehicleService`, `VehicleMapper` take the `Driver` entity to build `VehicleResponse.CurrentDriver` | (c) | Tolerated; an entity crosses the boundary |
| vehicle → assignment | `VehicleSpecifications` subquery on `VehicleDriverAssignment` (search by current driver name, FR-V08) | (d) | Tolerated, listed |
| document, maintenance, assignment → vehicle | `DocumentVehicleUsage`, `MaintenanceVehicleUsage`, `AssignmentVehicleUsage` implement `VehicleUsage` | (b) | Yes. This replaced a native `hasHistory` SQL in `VehicleRepository` that read three foreign tables (removed 2026-11-07) |
| driver → assignment | `DriverService` uses `AssignmentService` (`currentVehiclesByDriverId`, `currentVehicleOf`, `closeCurrentForDriver`, `driverHasHistory`) | (a) | Yes, but it closes the cycle driver ↔ assignment |
| driver → vehicle | `DriverService`, `DriverMapper` take the `Vehicle` entity for `DriverResponse.CurrentVehicle` | (c) | Tolerated; an entity crosses the boundary |
| driver → assignment | `DriverSpecifications` subquery on `VehicleDriverAssignment` (`available` filter) | (d) | Tolerated, listed |
| assignment → vehicle, driver | `AssignmentService` uses `VehicleRepository.findForUpdateById`/`existsById`, `DriverRepository.findForUpdateById`/`existsById`, `VehicleStatus`; `VehicleDriverAssignment` has `@ManyToOne` to both | (c) | Yes (row locks and references to root aggregates) |
| document → vehicle | `DocumentService` uses `VehicleRepository.findById`/`existsById`; `VehicleDocument` has `@ManyToOne Vehicle` | (c) | Yes |
| maintenance → vehicle | `MaintenanceService` uses `VehicleRepository.findById`/`existsById`; `MaintenanceRecord` has `@ManyToOne Vehicle` | (c) | Yes |
| dashboard → document, driver | `DashboardService` uses `DocumentService.findExpiring`, `DriverService.findExpiringLicenses` | (a) | Yes |
| dashboard → vehicle, driver, document, maintenance, assignment | `DashboardService` uses the five repositories directly for COUNT/SUM queries | read-only repository use | Accepted for the read model only: no writes, one REPEATABLE READ transaction. Not allowed for any other context |
| dashboard → document, driver (DTOs) | `DashboardSummaryResponse` embeds `DocumentResponse`, `DriverResponse` | DTO reuse | Yes |
| dev → every context | `DemoDataSeeder` writes entities of vehicle, driver, assignment, document (and `StorageService`), maintenance | writes foreign tables | Accepted exception: only with `app.seed-demo-data=true` (local profile) |
| common → document | `GlobalExceptionHandler` imports `document.storage.StorageException` (503 mapping) | shared kernel → context | **No.** Follow-up: move `StorageException` to `common/exception` or put the handler in the `document` package |
| common → config | `ExpiryCalculator` imports `ClockConfig.BUSINESS_ZONE` | kernel → infrastructure | Tolerated (constant only) |
| config → auth | `SecurityConfig` wires `JwtAuthenticationFilter`, `JwtService`, the 401/403 handlers; `JpaAuditingConfig` reads `AuthenticatedUser` | wiring | Yes |

### Every existing cross-feature dependency (frontend, `core/` and `shared/` excluded unless noted)

Rule:
- A feature may import another feature's **data models** (read-only types) and **public widgets** for
  composition, and the shared change counters from `core/utils/change_tracker.dart`.
- It must not call another feature's repository or change another feature's query state.

| From → to | Where | Follows the rule? |
|---|---|---|
| vehicles, drivers, documents, maintenance, dashboard → auth | `currentUserProvider` / `isAdmin` for showing buttons; query controllers reset on user change | Yes |
| vehicles → assignments, documents, maintenance | `vehicle_detail_screen.dart` embeds `assign_driver_dialog`, `assignment_history_view`, `documents_tab`, `maintenance_tab` and reads `assignment_providers` | Yes (composition) |
| assignments → vehicles | `assignment_providers.dart` invalidates `vehicleDetailProvider`; `assign_driver_dialog.dart` imports the `Vehicle` model | Yes, but closes the cycle vehicles ↔ assignments |
| assignments → drivers | `assignment_providers.dart` calls `driverRepositoryProvider.list(...)` (`assignableDriversProvider`); the dialog uses the `Driver`/`DriverQuery` models and `driver_widgets.dart` | **Partly.** Calling the drivers repository is a deviation, tolerated because assignment is a composition of vehicle and driver |
| drivers → assignments | `driver_detail_screen.dart` embeds `assignment_history_view.dart` | Yes |
| documents → vehicles | `expiring_documents_screen.dart` uses `VehicleDetailTab` to deep-link to the Documents tab | Yes, but closes the cycle vehicles ↔ documents |
| dashboard → documents, drivers | `dashboard_summary.dart` parses `VehicleDocument` and `Driver`; `dashboard_providers.dart` watches the document and maintenance counters | Yes |
| dashboard → drivers, vehicles | `dashboard_screen.dart` calls `driverQueryProvider.notifier.clearFilters()` / `showByLicenceExpiry()` and `vehicleQueryProvider.notifier.clearFilters()`; imports `ExpiringDocumentTile` from a screen file | **No** (finding SOLID-FE-12): it changes other features' state and imports a screen file. Follow-up: navigate with query parameters, as for vehicles |
| shared → auth | `shared/widgets/app_shell.dart` imports `auth_models`, `auth_controller` | **No**, the shared kernel depends on a feature. Tolerated: the shell is the signed-in frame |
| core/router → every feature | `app_router.dart` imports all screens | Yes, it is the composition root |

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Any service may use any repository | Leads to the native `hasHistory` query that read three foreign tables. That query existed in v2.0 until 2026-11-07 and was replaced by `VehicleUsage`. |
| Database per context / separate schemas | One free Render PostgreSQL. Cross-context FKs with `ON DELETE RESTRICT` are the BR-10 safety net, and they need one schema. |
| Domain events (Spring `ApplicationEvent`) for side effects, e.g. "vehicle became INACTIVE" | Asynchronous or after-commit listeners would break the BR-05 rule that closing the assignment happens in the same transaction. A synchronous `@EventListener` adds indirection without decoupling for this size. |
| Generated API client from OpenAPI | Adds a code generator to both builds. The hand-written contract plus the models in `features/*/data` was enough for ~30 endpoints. |
| Return entities from controllers | Guideline section 9 rule; it would leak `passwordHash` and cause lazy-loading errors, since `spring.jpa.open-in-view=false`. |

## Consequences

**Positive**
- Each table has one place where its rules live, so BR-01, BR-06, BR-07, BR-09 and BR-10 each have one owner.
- The frontend and backend agree through a written contract.

**Negative / accepted trade-offs**
- The package cycle `vehicle ↔ assignment ↔ driver` remains (SOLID-BE-03):
  - `VehicleService` depends on the whole `AssignmentService`, including assign/unassign, though it needs only
    "current driver of" and "close on INACTIVE".
  - Entities (`Driver`, `Vehicle`) cross the boundary.
- `dashboard` couples to five repositories, so a column rename in any context can break it.
- The rules are not machine-checked.

**Follow-ups**
- Narrow ports in place of the whole `AssignmentService`: for example a `CurrentDriverLookup` owned by `vehicle`
  and a `CurrentVehicleLookup` owned by `driver`, both returning small views instead of entities.
- Fix the `common → document` import.
- Fix the dashboard navigation (SOLID-FE-12).
- Add the architecture test from 0002.
- Any contract change starts with a pull request to `docs/API_CONTRACT.md` and an ADR when it is not additive.

## Related

- Guideline: sections 7, 9 (rule on entities), 10 (FKs `ON DELETE RESTRICT`), 11 (contract), BR-05, BR-10.
- ADRs: [0002](0002-bounded-contexts-and-package-by-feature.md), [0012](0012-delete-policy-br-10.md),
  [0014](0014-driver-vehicle-assignment-model.md).
- Files: `docs/API_CONTRACT.md`, `docs/ER_DIAGRAM.md`, `MIG/V1`–`V6`, `BE/vehicle/VehicleUsage.java`,
  `BE/assignment/AssignmentService.java`, `BE/dashboard/DashboardService.java`,
  `BE/common/exception/GlobalExceptionHandler.java`, `FE/core/utils/change_tracker.dart`.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
