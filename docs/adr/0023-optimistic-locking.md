# 0023. Optimistic locking: a `version` on vehicles, required in `PUT /vehicles/{id}`

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** before implementation (decision D2 of 2026-11-07, taken for both assignments; the code does not exist
  yet; this ADR gets file and test references when the change is merged)
- **Implemented:** 2026-11-08 (v1.1) — see "Implementation" at the end; the decision above is unchanged
- **Deciders:** project owner

The same decision, with the same number, exists in Assignment 2 (`assignment 2/docs/adr/0023-optimistic-locking.md`),
where it also covers drivers, maintenance records and users. In v1.0 the only editable record is the vehicle, so
this copy covers **vehicles only** (v1.1 hardening).

## Context

There is no optimistic locking on vehicles (finding **NFR-REL-01**, see
[0015](0015-non-functional-requirements-strategy.md)): no `@Version`, no `ETag`/`If-Match`. When two people open the
same vehicle and both save, the second `PUT` silently overwrites the first one ("lost update").

`VehicleService.update` (`BE/vehicle/VehicleService.java`) loads the vehicle with a plain `findById` (no row lock),
checks the duplicate number (BR-02) and saves. Nothing compares the data the form was filled from with the stored
row.

Forces: guideline section 17.2 ("Data"), NFR-REL-01, Flyway rule "never edit an existing migration"
([0006](0006-postgresql-everywhere-flyway-and-validate.md)), Problem Details for every error
([0008](0008-error-format-problem-details.md)), DTOs at the edges ([0005](0005-layered-architecture-and-solid-conventions.md)).

## Decision

**The vehicle gets a JPA `@Version Long version`, returned in `VehicleResponse` and required in
`PUT /vehicles/{id}`. A different version is refused with 409 `stale-version`.**

- **Database:** new migration `MIG/V2_1__add_vehicle_version.sql`:
  ```sql
  ALTER TABLE vehicles ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
  ```
  Existing rows start at 0. V1 and V2 are not edited.
- **Entity:** `Vehicle` gets `@Version @Column(name = "version", nullable = false) private Long version;` with a
  getter only; application code never sets it.
- **Response:** `VehicleResponse` gets `"version": <number>`.
- **Request:** `UpdateVehicleRequest` gets `@NotNull Long version` (missing → 400 `validation` with
  `errors[{field: "version", message: "Version is required."}]`). `CreateVehicleRequest` does not have it; a
  `version` in a `POST` body is ignored. The two records no longer have the same shape.
- **Check** in `VehicleService.update`, after loading and before any change:
  `if (!vehicle.getVersion().equals(request.version())) throw new StaleVersionException("vehicle");`
  There is no row lock in v1.0, so a concurrent commit between the check and the flush is caught by Hibernate's
  versioned `UPDATE … WHERE version = ?`; the resulting `ObjectOptimisticLockingFailureException` is mapped by
  `GlobalExceptionHandler` to **the same 409**.
- **Error:** 409 Problem Details, **no `errors[]`**:
  ```json
  {
    "type": "https://stark-vms/errors/stale-version",
    "title": "Record changed",
    "status": 409,
    "detail": "This vehicle was changed by someone else since you opened it. Reload and try again.",
    "instance": "/api/v1/vehicles/12"
  }
  ```
  New constant `ApiErrorType.STALE_VERSION`.
- **Server-side changes:** v1.0 has no `PATCH` status endpoint, so only `PUT` changes a vehicle. Any future
  server-side mutation bumps the version automatically, and an edit form opened before it gets 409 (accepted, as in
  Assignment 2).
- **Frontend:** `FE/features/vehicles/data/vehicle.dart` parses `version`; `vehicle_form_controller.dart` keeps the
  version the form was filled from and sends it in the `PUT` (not in the create request). On 409 `stale-version`
  the form shows a dialog **"Changed by someone else"** with the server's detail and "Reloading replaces your unsaved
  changes." — **Reload** refetches the vehicle and refills the form (local edits discarded); **Cancel** keeps the
  form as it is. The duplicate-number 409 keeps its field error.
- **Tests (part of the implementation):** service unit tests (match / mismatch); MockMvc (missing version → 400; two
  `PUT`s with the same version → 200 with `version + 1`, then 409 without `errors`); a concurrent-flush test mapping
  to 409; a widget test for the dialog and Reload.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `ETag` / `If-Match` (412) | HTTP-native, but needs CORS-exposed headers and header handling in every client call; the frontend already handles 409 Problem Details. A JSON field is simpler and visible in Swagger. |
| Pessimistic locking only (`SELECT … FOR UPDATE`) | Serialises concurrent transactions but cannot see that a form was filled minutes ago from older data. |
| Last write wins (today) | Silent data loss (NFR-REL-01). |
| Optional `version` in `PUT` | Clients that omit it would keep last-write-wins; frontend and backend ship together. |
| Compare `updatedAt` | Precision and clock issues; JPA already has `@Version`. |

## Consequences

**Positive**
- Lost updates on vehicles become a visible 409 with a clear recovery.
- One mechanism covers both stale forms and races between transactions, without adding a row lock.

**Negative / accepted trade-offs**
- **Not purely additive:** a `PUT /vehicles/{id}` without `version` is now a 400; frontend and backend deploy
  together ([0016](0016-deployment-render-and-netlify.md)).
- On reload the user's unsaved edits are lost (they are told so).

**Follow-ups**
- Optionally an `ETag` / `If-Match` layer on the same column.
- Update [0015](0015-non-functional-requirements-strategy.md) (NFR-REL-01), `docs/ER_DIAGRAM.md` (V2_1) and the
  backend README after implementation.

## Related

- Guideline: sections 4, 13, 17.2 (Data, Errors, Tests); BR-02 (duplicate check on edit unchanged).
- NFR findings: **NFR-REL-01** (closed by this ADR); NFR-SEC-01 is handled by [0022](0022-login-throttling.md).
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0006](0006-postgresql-everywhere-flyway-and-validate.md),
  [0008](0008-error-format-problem-details.md), [0009](0009-vehicle-number-normalization-and-duplicate-check.md),
  [0015](0015-non-functional-requirements-strategy.md).
- Files (planned): `MIG/V2_1__add_vehicle_version.sql`, `BE/vehicle/Vehicle.java`, `BE/vehicle/VehicleService.java`,
  `BE/vehicle/dto/UpdateVehicleRequest.java`, `BE/vehicle/dto/VehicleResponse.java`,
  `BE/common/exception/ApiErrorType.java`, `BE/common/exception/GlobalExceptionHandler.java`,
  `FE/features/vehicles/data/vehicle.dart`, `FE/features/vehicles/presentation/vehicle_form_controller.dart`,
  `FE/features/vehicles/presentation/vehicle_form_screen.dart`, `docs/API_CONTRACT.md` §6.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.

## Implementation (2026-11-08, v1.1)

Implemented as decided, for vehicles only.

- **Migration number (2026-11-08, before the first deployment):** the file was renamed from `V3__add_vehicle_version.sql` to `V2_1__add_vehicle_version.sql`. v2.0 already uses V3 for `drivers`, and both versions share one repository and one Render database, so a v1 `V3` would fail Flyway validation on the upgrade. v2.x ships the byte-identical `V2_1` file and its V7 no longer adds `vehicles.version`. No database had applied the old name.
- **Backend:** `V2_1__add_vehicle_version.sql`; `vehicle/Vehicle` has `@Version Long version`; `VehicleResponse` carries
  `version`; `UpdateVehicleRequest` requires it (`@NotNull`, "Version is required."), `CreateVehicleRequest` has none
  (a sent `version` is ignored). `VehicleService.update` compares the sent and the stored version right after loading,
  before the duplicate-number check, and throws `StaleVersionException` (v1.0 has no row lock on vehicles, so a race
  after that comparison is caught by Hibernate's versioned `UPDATE`; `GlobalExceptionHandler` maps
  `ObjectOptimisticLockingFailureException` to the same 409, naming the record from the entity class).
- **Settled during implementation:** a `PUT` that changes no value writes nothing, so the `version` stays (contract
  6.2 says so now).
- **Frontend:** `Vehicle.version` (missing = 0), `VehicleInput.toUpdateJson(version)`,
  `VehicleRepository.update(id, input, version:)`, `VehicleFormState.staleVersionMessage`,
  `shared/widgets/stale_version_dialog.dart`; the edit form is keyed by `(id, version)` so Reload refills it.
- **Tests:** `VehicleOptimisticLockingApiTest` (7, incl. a concurrent commit between read and flush and the V2_1 column),
  two cases in `VehicleServiceTest`, three in `GlobalExceptionHandlerTest`, `VehicleApiTest` PUT bodies send
  `version`; frontend `test/unit/optimistic_locking_test.dart` (9) and `test/widget/stale_version_test.dart` (2).
