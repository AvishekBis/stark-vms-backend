# 0014. Driver–vehicle assignment model: history table, partial unique indexes and row locks

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v2.0 built FR-DR02)
- **New in v2.0**

## Context

FR-DR02: "Assign or change a vehicle's driver. One current driver per vehicle and one current vehicle per
driver. Previous assignment closed, history kept." BR-05: only ACTIVE vehicles and ACTIVE drivers can be
assigned, and assigning closes the previous open assignment in the same transaction. BR-06: a driver has at most
one current vehicle and a vehicle at most one current driver; enforced by the service (409) and by partial unique
indexes. Guideline 10: "Driver assignment is its own table so that history is kept instead of overwritten"
(10 intro), and the partial unique indexes in 10.3. TC-10: assigning a driver who already drives another vehicle
gives 409.

Two users can assign at the same moment: the same driver to two vehicles, two drivers to one vehicle, or an
assignment while an ADMIN sets the vehicle INACTIVE.

## Decision

- **One history table** `vehicle_driver_assignments` (`MIG/V4__create_assignments.sql`) owned by the `assignment`
  context. Each row is one period: `vehicle_id`, `driver_id`, `assigned_at`, `unassigned_at` (`NULL` = current).
  - A row is never overwritten. Changing the driver closes the open row (`unassigned_at = now`) and inserts a new
    one; the closed row and the new row get the same timestamp (`AssignmentService.now()`, clock truncated to
    microseconds).
  - `CHECK (unassigned_at IS NULL OR unassigned_at >= assigned_at)` (`ck_assignments_period`); both FKs
    `ON DELETE RESTRICT` ([0012](0012-delete-policy-br-10.md)).
  - `assignedBy` in the API is the row's audit `created_by`.
- **BR-06 at the database:** partial unique indexes `uq_assignment_open_vehicle (vehicle_id)` and
  `uq_assignment_open_driver (driver_id)`, both `WHERE unassigned_at IS NULL`. History indexes
  `(vehicle_id, assigned_at DESC)` and `(driver_id, assigned_at DESC)`.
- **Rules in one transaction** (`BE/assignment/AssignmentService.assign`, contract §6.4 rules 1–5):
  1. Lock the vehicle row, then the driver row (`findForUpdateById`, `PESSIMISTIC_WRITE`). Missing → 404. Either
     not ACTIVE → 409 `assignment-not-allowed` (`requireAssignable`).
  2. The same driver again → 200 with the existing assignment (idempotent).
  3. The driver currently drives another vehicle → 409 `driver-already-assigned`, field `driverId` (TC-10,
     `requireDriverFree`).
  4. Close the vehicle's open row, `flush()` so the partial index is free, then `saveAndFlush` the new row
     (`openAssignment`).
  5. A violation of either partial index (a race the checks did not see) → the same 409
     `driver-already-assigned` (`isOpenAssignmentViolation`, and `AssignmentConstraintConfig` for the global
     handler).
- **Unassign** (`DELETE /vehicles/{id}/driver`) takes the same vehicle row lock and closes the open row; no open
  row → 409 `no-current-driver`.
- **Status changes and assignments:**
  - `VehicleService.changeStatus` locks the vehicle and, for INACTIVE, calls
    `AssignmentService.closeCurrentForVehicle` in the same transaction. MAINTENANCE keeps the driver.
  - `DriverService.update` locks the driver and, for INACTIVE, calls `closeCurrentForDriver`.
  - `VehicleService.update` (PUT) also takes the vehicle row lock, so a full-row update cannot write back a stale
    status after a concurrent PATCH (added 2026-11-07).
- **Reads:**
  - `VehicleResponse.currentDriver` and `DriverResponse.currentVehicle` are computed from the open row. For a
    page, one `IN (...)` query loads the current drivers (or vehicles) of all rows (`currentDriversByVehicleId`,
    `currentVehiclesByDriverId`).
  - Histories are paged, newest first: `GET /vehicles/{id}/assignments`, `GET /drivers/{id}/assignments`.
  - `GET /drivers?available=true` (ACTIVE drivers without an open row, `DriverSpecifications.available`) feeds
    the assign dialog.
- **Frontend:** `FE/features/assignments/` has the assign dialog and the history view, embedded in the vehicle and
  driver detail screens. `Vehicle.canReceiveDriver` (BR-05) decides whether "Assign" is offered;
  `Vehicle.endsAssignmentOn` and `Driver.losesVehicleOn` drive the warnings in the status dialog and the driver
  form. Every assign/unassign bumps the vehicle and driver change counters ([0004](0004-frontend-state-management-with-riverpod.md)).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `current_driver_id` column on `vehicles` | Loses the history (FR-DR02 "history kept"); guideline 10 asks for a separate table. |
| Current column plus a separate history/audit table | Two writes for every change and two sources of truth that can disagree. One table where `unassigned_at IS NULL` means "current" has one source. |
| Service checks only, no partial indexes | Two concurrent requests can both pass the checks (BR-06 names the indexes as the safety net). |
| Partial indexes only, no service checks | Every conflict would surface as a database exception without a readable detail ("Driver X is already assigned to vehicle Y"). |
| Optimistic locking (`@Version`) on vehicle/driver | Would turn concurrent assignments into retries for the client; the row lock serializes them on the server and the requests are short. |
| An exclusion constraint on time ranges (`tstzrange`, `btree_gist`) | Would also forbid overlapping *closed* periods, which nobody asked for, and needs an extension. The two partial indexes express exactly BR-06. |

## Consequences

**Positive**
- BR-05 and BR-06 hold under concurrency: covered by `AssignmentServiceTest` (11 tests, including the partial-index
  race) and `AssignmentApiTest` (14 tests against the real indexes, including "status INACTIVE auto-closes",
  "MAINTENANCE keeps the driver" and "driver INACTIVE auto-closes").
- Full history per vehicle and per driver, used by BR-10 (a vehicle or driver with any row cannot be deleted).
- The "current driver" query is an index lookup on the partial index (`docs/PERFORMANCE.md`: 0.1 ms per page).

**Negative / accepted trade-offs**
- Row locks serialize all writes to one vehicle (PUT, PATCH status, assign, unassign). At this scale that is not
  measurable; there is no `lock_timeout`, so a stuck transaction would block the others until it ends.
- `assignment` reads the `vehicle` and `driver` repositories and entities, and `vehicle`/`driver` call
  `AssignmentService`; this is the package cycle recorded in [0003](0003-data-ownership-and-cross-context-contracts.md)
  (SOLID-BE-03).
- Vehicle and driver updates have no optimistic locking: two users editing the same vehicle form both succeed and
  the last PUT wins (NFR-REL-01, see [0015](0015-non-functional-requirements-strategy.md)). The row lock prevents a
  stale *status*, not a lost edit.
- The constraint-race mapping exists twice (service and `AssignmentConstraintConfig`), see NFR-MAINT-01 in 0005.

**Follow-ups**
- `@Version` columns (new migration) plus `If-Match`/409 for vehicle, driver and maintenance edits.
- A `lock_timeout` for the row-locking transactions.
- Narrow ports instead of the whole `AssignmentService` (0003).

## Related

- Guideline: sections 10 (intro), 10.1, 10.3, 12 BR-05, BR-06, FR-DR02, TC-10.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0006](0006-postgresql-everywhere-flyway-and-validate.md),
  [0011](0011-time-and-expiry-with-injected-clock.md), [0012](0012-delete-policy-br-10.md).
- Files: `MIG/V4__create_assignments.sql`, `BE/assignment/AssignmentService.java`,
  `BE/assignment/VehicleDriverAssignment.java`, `BE/assignment/VehicleDriverAssignmentRepository.java`,
  `BE/assignment/AssignmentConstraintConfig.java`, `BE/vehicle/VehicleRepository.java` (`findForUpdateById`),
  `BE/driver/DriverRepository.java`, `BE/driver/DriverSpecifications.java`, `BE/vehicle/VehicleService.java`,
  `BE/driver/DriverService.java`, `FE/features/assignments/`, `docs/API_CONTRACT.md` §6.4.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
