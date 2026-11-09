# 0023. Optimistic locking: a `version` on every editable record, required in every PUT

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** before implementation (decision D2 of 2026-11-07; the code does not exist yet; this ADR gets file
  and test references when the change is merged)
- **Deciders:** project owner

## Context

There is no optimistic locking on vehicles, drivers and maintenance records (finding **NFR-REL-01**, see
[0015](0015-non-functional-requirements-strategy.md)): no `@Version`, no `ETag`/`If-Match`. When two people open the
same record and both save, the second `PUT` silently overwrites the first one ("lost update").

What exists today:
- `VehicleService.update`, `VehicleService.changeStatus` and `DriverService.update` load the row with
  `findForUpdateById` (`PESSIMISTIC_WRITE`, [0014](0014-driver-vehicle-assignment-model.md)). This serialises two
  *concurrent* transactions, but it cannot detect that the second user's form was filled from data that is already
  stale. The lock lasts milliseconds; the gap between "open form" and "save" lasts minutes.
- `MaintenanceService.update` has no row lock at all.
- [0024](0024-admin-user-management.md) adds `PUT /users/{id}`, which has the same problem.

Forces: guideline section 17.2 ("Data": missing transaction / correctness under concurrency), NFR-REL-01, Flyway
rule "never edit an existing migration" ([0006](0006-postgresql-everywhere-flyway-and-validate.md)), Problem
Details for every error ([0008](0008-error-format-problem-details.md)), DTOs at the edges, entities never leave
services ([0005](0005-layered-architecture-and-solid-conventions.md)).

## Decision

**Every editable record gets a JPA `@Version Long version`. The version is sent to the client in every response and
must be sent back in every `PUT`. If it does not match the stored version, the update is refused with 409
`stale-version`.**

**Database** — one new migration `MIG/V7__add_version_and_user_management.sql` (shared with
[0024](0024-admin-user-management.md)):
```sql
ALTER TABLE vehicles            ADD COLUMN version BIGINT NOT NULL DEFAULT 0; -- moved to V2_1, see the note below
ALTER TABLE drivers             ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE maintenance_records ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users               ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
-- plus the user-management columns of ADR 0024
```
Existing rows start at version 0. Assignments and documents are not edited with `PUT` and get no version.

**Entities** — `Vehicle`, `Driver`, `MaintenanceRecord` and `User` get `@Version @Column(name = "version",
nullable = false) private Long version;` with a getter only. Application code never sets it; Hibernate increments it
on every flushed change and adds `WHERE version = ?` to the `UPDATE`.

**Responses** — `VehicleResponse`, `DriverResponse`, `MaintenanceResponse` and the admin `UserResponse` of 0024 get
`"version": <number>`.

**Requests**
- `PUT /vehicles/{id}`, `PUT /drivers/{id}`, `PUT /maintenance/{id}` and `PUT /users/{id}` **require** `version`
  (`@NotNull Long version`). Missing → 400 `validation` with `errors[{field: "version", message: "Version is
  required."}]`.
- Create requests (`POST`) do **not** send a version (a new record starts at 0). `DriverRequest` and
  `MaintenanceRequest` are used today for both create and update, so each gets an update variant
  (`UpdateDriverRequest`, `UpdateMaintenanceRequest`), like the existing `CreateVehicleRequest` /
  `UpdateVehicleRequest` pair. A `version` sent in a `POST` body is ignored (Jackson ignores unknown properties).

**Check** — in the service, after loading the entity (with the existing row lock where there is one: vehicle and
driver), before any change:
```java
if (!vehicle.getVersion().equals(request.version())) {
    throw new StaleVersionException("vehicle");
}
```
- With the row lock (vehicles, drivers) the comparison is final: nobody can change the row between the check and the
  commit.
- Without a row lock (maintenance records, users outside the last-admin path) a concurrent commit between the check
  and the flush is caught by Hibernate's versioned `UPDATE`, which throws `ObjectOptimisticLockingFailureException`.
  `GlobalExceptionHandler` maps that exception to **the same 409** (the record noun is derived from the entity class
  name; "record" if unknown).

**Error** — 409 Problem Details, **no `errors[]`** (the conflict is about the whole record, not one field):
```json
{
  "type": "https://stark-vms/errors/stale-version",
  "title": "Record changed",
  "status": 409,
  "detail": "This vehicle was changed by someone else since you opened it. Reload and try again.",
  "instance": "/api/v1/vehicles/12"
}
```
`detail` uses the noun `vehicle`, `driver`, `maintenance record` or `user`. New constant
`ApiErrorType.STALE_VERSION`.

**Server-side changes bump the version too.** Any flushed change does: `PATCH /vehicles/{id}/status`, a driver set to
`INACTIVE`, disabling a user, a password reset. An edit form opened before such a change gets 409 on save. This is
accepted and documented: the user reloads and sees the new status. Changes that only lock a row without changing it
(assign / unassign a driver lock vehicle and driver with `PESSIMISTIC_WRITE`) do not bump the version.

**Frontend**
- The models `Vehicle`, `Driver`, `MaintenanceRecord` (and `User` from 0024) parse `version`.
- Edit forms keep the version of the record they were filled from and send it in the `PUT`
  (`vehicle_form_controller.dart`, `driver_form_controller.dart`, `maintenance_form_dialog.dart`, the users form).
  Create requests do not send it.
- On 409 with type `stale-version` the form shows a dialog **"Changed by someone else"** with the server's detail and
  the sentence "Reloading replaces your unsaved changes." Actions:
  - **Reload** — refetches the record, refills the form with the current values and the new version (the local
    edits are discarded, as the dialog said);
  - **Cancel** — closes the dialog and keeps the form as it is, so the user can copy their text; saving again will
    fail again until they reload.
- Other 409s (duplicate number, phone, licence) keep showing their field error as today.

**Tests (part of the implementation)**
- Service unit tests: matching version updates; different version → `StaleVersionException`; missing version → 400
  in MockMvc.
- MockMvc: two `PUT`s with the same version → first 200 with `version + 1`, second 409 `stale-version` without
  `errors`; `PATCH` status bumps the version.
- Repository/integration test: a concurrent flush raises `ObjectOptimisticLockingFailureException`, which maps to 409.
- Widget tests: the stale dialog appears and "Reload" refills the form.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `ETag` / `If-Match` headers (412 Precondition Failed) | The HTTP-native form of the same idea. It needs CORS to expose `ETag`, every client call to read and send headers, `GET` list rows would still need the version in the body for "edit from list", and 412 is unfamiliar to the frontend code, which already handles 409 Problem Details. A `version` field in the JSON is simpler, visible in Swagger and testable in MockMvc. Could be added later on top of the same column. |
| Pessimistic locking only (the existing `findForUpdateById`) | Serialises concurrent transactions but cannot see that a form was filled minutes ago from older data. Holding a database lock while a user edits a form is not possible over stateless HTTP. |
| Last write wins (keep today's behaviour) | Silent data loss, the finding NFR-REL-01 itself. |
| Optional `version` in `PUT` (check only when sent) | Old or buggy clients would silently keep last-write-wins; the frontend and backend ship together, so a required field costs nothing. |
| Compare `updatedAt` instead of a version | Timestamp precision and clock issues; `updatedAt` is set by auditing, not by the database, and two changes in the same millisecond would collide. JPA already has `@Version`. |
| Field-level merge (only reject if the same field changed) | Needs the original values from the client and per-field rules; too complex for the benefit in a small fleet tool. |

## Consequences

**Positive**
- Lost updates on vehicles, drivers, maintenance records and users become a visible 409 with a clear recovery
  ("Reload").
- One mechanism (`@Version`) covers both stale forms and races between transactions.
- The change is mostly additive for clients: one new response field and one new required `PUT` field.

**Negative / accepted trade-offs**
- **Not purely additive:** a `PUT` without `version` is now a 400. The frontend and backend must be deployed together
  (they already are, [0016](0016-deployment-render-and-netlify.md)).
- A form opened before a status change, a deactivation or a password reset gets 409 although the user did not touch
  that field.
- On reload the user's unsaved edits are lost (they are told so, and Cancel keeps them visible).
- Two more request records (`UpdateDriverRequest`, `UpdateMaintenanceRequest`).

**Follow-ups**
- Optionally also return an `ETag` header and accept `If-Match`.
- Update [0015](0015-non-functional-requirements-strategy.md) (NFR-REL-01), `docs/ER_DIAGRAM.md` (V7) and the
  backend README after implementation.

## Related

- Guideline: sections 4 (STAFF and ADMIN both edit records), 13, 17.2 (Data, Errors, Tests); BR-02 (the duplicate
  check on edit is unchanged).
- NFR findings: **NFR-REL-01** (lost updates, closed by this ADR); NFR-SEC-01 is handled by
  [0022](0022-login-throttling.md).
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0006](0006-postgresql-everywhere-flyway-and-validate.md),
  [0008](0008-error-format-problem-details.md), [0014](0014-driver-vehicle-assignment-model.md),
  [0015](0015-non-functional-requirements-strategy.md), [0024](0024-admin-user-management.md).
- Files (planned): `MIG/V7__add_version_and_user_management.sql`, `BE/vehicle/Vehicle.java`,
  `BE/vehicle/VehicleService.java`, `BE/vehicle/dto/UpdateVehicleRequest.java`, `BE/vehicle/dto/VehicleResponse.java`,
  `BE/driver/Driver.java`, `BE/driver/DriverService.java`, `BE/driver/dto/UpdateDriverRequest.java`,
  `BE/maintenance/MaintenanceRecord.java`, `BE/maintenance/MaintenanceService.java`,
  `BE/maintenance/dto/UpdateMaintenanceRequest.java`, `BE/auth/User.java`, `BE/common/exception/ApiErrorType.java`,
  `BE/common/exception/GlobalExceptionHandler.java`, `FE/features/vehicles/`, `FE/features/drivers/`,
  `FE/features/maintenance/`, `docs/API_CONTRACT.md` §7.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.

## Note: migration numbering (2026-11-08, before the first deployment)

`vehicles.version` is added by `V2_1__add_vehicle_version.sql`, byte-identical to the Assignment 1 (v1.1) file, and V7 no longer adds it. Both versions share one repository and one Render database; v1.1 originally numbered this file V3, which v2.0 uses for `drivers`, so the upgrade would have failed Flyway validation. No database had applied V7 yet. The decision above is unchanged.
