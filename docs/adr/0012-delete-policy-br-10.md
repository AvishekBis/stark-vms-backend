# 0012. Delete policy (BR-10): hard delete only without history, INACTIVE instead

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v2.0 added documents,
  maintenance and assignments; v1.0 had an unconditional hard delete because no history tables existed. The
  check was moved from a native SQL query to the `VehicleUsage` port on 2026-11-07)
- **New in v2.0**

## Context

BR-10: "A vehicle with documents, maintenance or assignment history cannot be hard-deleted — return 409 and
suggest INACTIVE. (Alternative: soft delete. Choose one and document it.)" FR-V04: delete is ADMIN-only with a
confirmation dialog. Guideline 10.2: "Foreign keys use ON DELETE RESTRICT: a vehicle with history cannot
silently lose it". Drivers have assignment history too. Vehicle status INACTIVE already exists (FR-V07) and means
"no longer in use".

## Decision

We choose **hard delete without history, otherwise 409 and INACTIVE**. There is no soft delete.

- **Vehicles** (`BE/vehicle/VehicleService.delete`, `DELETE /vehicles/{id}`, ADMIN):
  - The vehicle is deleted only when no `VehicleUsage` reports a record for it.
  - The usages are `DocumentVehicleUsage` (`existsByVehicleId` on `vehicle_documents`),
    `MaintenanceVehicleUsage` (`maintenance_records`) and `AssignmentVehicleUsage` (open *or closed* rows of
    `vehicle_driver_assignments`).
  - Otherwise the response is 409 `.../errors/vehicle-has-history`, title "Vehicle has history", detail
    "Vehicle DHK-1234 has documents, maintenance or driver history and cannot be deleted. Set its status to
    INACTIVE instead."
- **Drivers** (`BE/driver/DriverService.delete`, ADMIN): a driver with any assignment row
  (`AssignmentService.driverHasHistory`) gets 409 `driver-has-history`.
- **Safety net:** every FK to `vehicles` and `drivers` is `ON DELETE RESTRICT` (V4, V5, V6), so even a missed or
  raced check cannot delete history.
- **INACTIVE instead of delete:**
  - `PATCH /vehicles/{id}/status` (ADMIN) to INACTIVE, and a driver update to INACTIVE, close the current
    assignment in the same transaction (only ACTIVE vehicles and drivers may have one, BR-05);
  - MAINTENANCE keeps the driver;
  - INACTIVE records stay visible and filterable (`?status=INACTIVE`).
- **Documents and maintenance records** are plain hard deletes (ADMIN). They are the history, not owners of
  history. Deleting a document also deletes its stored file ([0013](0013-document-file-storage-behind-storageservice.md)).
- **Frontend:**
  - `FE/features/vehicles/presentation/widgets/delete_vehicle_flow.dart` asks for confirmation. On a 409
    `vehicle-has-history` (`VehicleActions.hasHistory`) it shows the reason with a "Set INACTIVE instead" action.
  - The driver widgets do the same with `DriverActions.hasHistory`.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Soft delete (`deleted_at` column, `@SQLRestriction`) | Every list, count, search and unique check would need a "not deleted" filter; a deleted vehicle's number would block re-registration (or the unique key would need to include `deleted_at`); the dashboard counts would need the same filter. INACTIVE already expresses "no longer in use" and keeps the record visible. |
| `ON DELETE CASCADE` | Deletes documents, maintenance and assignment history with the vehicle — exactly what BR-10 forbids. |
| Check history with one native SQL query in `VehicleRepository` | This was the v2.0 implementation until 2026-11-07. It made `vehicle` read three tables owned by other contexts (against [0003](0003-data-ownership-and-cross-context-contracts.md)), so a new history table would need an edit in `vehicle`. Replaced by `VehicleUsage`. |
| Archive table (move the vehicle and its history) | More schema and code for a requirement nobody asked for. |

## Consequences

**Positive**
- History can never be lost silently: the rule is checked in the service, and the FKs enforce it.
- A new kind of vehicle record is covered by adding one `VehicleUsage` bean in its own context. `VehicleService`
  does not change.
- Covered by `VehicleServiceTest` (BR-10 through a mocked `VehicleUsage`), `VehicleApiTest` (409 for
  maintenance, a closed assignment and a document) and `DriverApiTest` (history delete 409).

**Negative / accepted trade-offs**
- A mistakenly created vehicle that already has a document cannot be removed through the API. The document must
  be deleted first, or the vehicle set INACTIVE.
- A concurrent insert of a history row between the usage check and the `DELETE` gives the FK violation. That
  becomes the generic 409 `conflict`, not `vehicle-has-history`, because no `UniqueConstraintRule` maps FK names.
- The usage check is three `exists` queries, one per context.

**Follow-ups**
- Map the RESTRICT FK names (`fk_documents_vehicle`, `fk_maintenance_vehicle`, `fk_assignments_vehicle`,
  `fk_assignments_driver`) to the `*-has-history` 409 in the global handler.

## Related

- Guideline: sections 10.2, 12 BR-05, BR-10, FR-V04, FR-V07, FR-DR01.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0014](0014-driver-vehicle-assignment-model.md).
- Files: `BE/vehicle/VehicleService.java`, `BE/vehicle/VehicleUsage.java`, `BE/document/DocumentVehicleUsage.java`,
  `BE/maintenance/MaintenanceVehicleUsage.java`, `BE/assignment/AssignmentVehicleUsage.java`,
  `BE/driver/DriverService.java`, `MIG/V4__create_assignments.sql`, `MIG/V5__create_documents.sql`,
  `MIG/V6__create_maintenance.sql`, `FE/features/vehicles/presentation/widgets/delete_vehicle_flow.dart`,
  `docs/API_CONTRACT.md` §6.2.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
