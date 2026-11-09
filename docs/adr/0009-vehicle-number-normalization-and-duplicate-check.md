# 0009. Vehicle number normalization and duplicate check in service + DB constraint

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 built FR-V05; v2.0
  reused the pattern for driver phone and licence numbers)

## Context

FR-V05 and BR-01: `DHK-1234`, `dhk 1234` and ` DHK1234 ` are the same vehicle number and must give 409 with a
clear message. BR-02: editing a vehicle without changing its number is allowed. The guideline (12.1) gives a
reference implementation and explains why to check in two places: two users can submit the same number at the
same moment, both pass the exists check, and only a database unique constraint stops the second insert, which
must then return the same 409. Bangladeshi registration numbers vary in format (`DHAKA METRO-GA 11-2345`), and
users want to see the number as they typed it.

## Decision

- **Normalization in one place:** `BE/vehicle/VehicleNumber.normalize`. It trims, uppercases with
  `Locale.ROOT` and removes all whitespace and hyphens (`[\s-]`).
- **Two columns** (`MIG/V2__create_vehicles.sql`):
  - `vehicle_number` keeps the number as entered (trimmed);
  - `number_key` holds the normalized key with `CONSTRAINT uk_vehicles_number_key UNIQUE (number_key)`.
- **Service check first** (`BE/vehicle/VehicleService.java`):
  - `create` checks `existsByNumberKey(key)`;
  - `update` checks `existsByNumberKeyAndIdNot(key, id)`, so the vehicle being edited is ignored (BR-02);
  - either one throws `DuplicateResourceException` with type `duplicate-vehicle-number`, field `vehicleNumber`
    and detail "Vehicle number DHK-1234 is already registered.".
- **Database constraint as the safety net.**
  - `saveAndFlush` flushes inside the transaction and turns a `DataIntegrityViolationException` on
    `uk_vehicles_number_key` into the same 409.
  - `VehicleConstraintConfig` registers a `UniqueConstraintRule` for the same constraint, so a violation raised
    anywhere else is also reported as that 409 by `GlobalExceptionHandler` ([0008](0008-error-format-problem-details.md)).
- **Validation is deliberately loose:** required, at most 40 characters, at least one letter or digit. Search
  matches the number as entered and the normalized key, so `dhk1234` finds `DHK-1234`.
- **Frontend:** the 409's `errors[0].field = vehicleNumber` (and the `type`, through `FormErrors.fromApi`) puts
  the message under the number field of the form.
- **v2.0 reuse:** drivers follow the same pattern.
  - `DriverPhone` normalizes and stores the phone, with `uk_drivers_phone`.
  - `LicenseNumber` builds `license_key`, with `uk_drivers_license_key`.
  - Each has a service check and a `DriverConstraintConfig` rule (409 `duplicate-driver-phone` /
    `duplicate-driver-license`).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Only the database constraint | The client would get a generic 409 (or 500) for the common case; the guideline wants a clear message on the field. |
| Only the service check | Lost update under concurrency: two inserts can both pass `exists` (guideline 12.1). |
| Store only the normalized number | Users would see `DHAKAMETROGA112345` instead of what they typed. |
| Functional unique index `UNIQUE (upper(regexp_replace(vehicle_number, ...)))` | Normalization would live in SQL and Java twice; the explicit column is simpler to query and to search. |
| Strict format validation (regex for Bangladeshi plates) | Formats vary by region and vehicle class; a strict regex would reject valid numbers. |
| Optimistic "insert and catch" only, no pre-check | Works, but every duplicate becomes an exception path through the database; the pre-check is cheaper and clearer. |

## Consequences

**Positive**
- TC-02 and TC-03 are covered by `VehicleNumberTest` (normalization cases), `VehicleServiceTest` (including the
  DB race → same 409) and `VehicleApiTest` against the real constraint.
- The same 409 regardless of which check fires.

**Negative / accepted trade-offs**
- Constraint name and message are defined twice (service and `VehicleConstraintConfig`), and the service
  matches the constraint by `message.contains(...)` on the driver's exception text (finding NFR-MAINT-01).
- A number that differs only in characters other than spaces and hyphens (e.g. `DHK–1234` with an en dash, or a
  dot) is not treated as the same number.

**Follow-ups**
- One mechanism for constraint races (see 0005).
- If real data shows other separators, extend `VehicleNumber.normalize` *and* add a migration that recomputes
  `number_key` (a new migration, never an edit of V2).

## Related

- Guideline: sections 7.1, 12 (BR-01, BR-02, BR-03), 12.1, FR-V05, TC-02, TC-03, TC-04.
- ADRs: [0008](0008-error-format-problem-details.md), [0005](0005-layered-architecture-and-solid-conventions.md).
- Files: `BE/vehicle/VehicleNumber.java`, `BE/vehicle/VehicleService.java`, `BE/vehicle/VehicleRepository.java`,
  `BE/vehicle/VehicleConstraintConfig.java`, `BE/vehicle/VehicleSpecifications.java`, `BE/driver/DriverPhone.java`,
  `BE/driver/LicenseNumber.java`, `BE/driver/DriverConstraintConfig.java`, `MIG/V2__create_vehicles.sql`,
  `MIG/V3__create_drivers.sql`, `FE/features/vehicles/presentation/vehicle_form_controller.dart`,
  `docs/API_CONTRACT.md` §2 "Duplicate vehicle number".

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
