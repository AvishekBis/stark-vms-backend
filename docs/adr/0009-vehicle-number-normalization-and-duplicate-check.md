# 0009. Vehicle number normalization and duplicate check in service + DB constraint

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 built FR-V05)

## Context

FR-V05 and BR-01 say that `DHK-1234`, `dhk 1234` and ` DHK1234 ` are the same vehicle number, and must give a 409
with a clear message. BR-02: editing a vehicle without changing its number is allowed.

Guideline 12.1 gives a reference implementation and explains why the check is made in two places. Two users can
submit the same number at the same moment and both pass the exists check. Only a database unique constraint stops
the second insert, and that insert must then return the same 409.

Bangladeshi registration numbers vary in format (`DHAKA METRO-GA 11-2345`), and users want to see the number as
they typed it.

## Decision

- **Normalization in one place.** `BE/vehicle/VehicleNumber.normalize` trims, uppercases with `Locale.ROOT` and
  removes all whitespace and hyphens (`[\s-]`).
- **Two columns** (`MIG/V2__create_vehicles.sql`):
  - `vehicle_number` keeps the number as entered (trimmed);
  - `number_key` holds the normalized key, with `CONSTRAINT uk_vehicles_number_key UNIQUE (number_key)`.
- **The service checks first** (`BE/vehicle/VehicleService.java`):
  - `create` checks `existsByNumberKey(key)`;
  - `update` checks `existsByNumberKeyAndIdNot(key, id)`, so the vehicle being edited is ignored (BR-02, TC-04);
  - a duplicate throws `DuplicateResourceException` with type `duplicate-vehicle-number`, field `vehicleNumber`
    and detail "Vehicle number DHK-1234 is already registered.".
- **The database constraint is the safety net.**
  - `saveAndFlush` flushes inside the transaction and turns a `DataIntegrityViolationException` on
    `uk_vehicles_number_key` into the same 409.
  - `VehicleConstraintConfig` registers a `UniqueConstraintRule` for the same constraint, so `GlobalExceptionHandler`
    reports a violation raised anywhere else as that 409 too ([0008](0008-error-format-problem-details.md)).
- **Validation is deliberately loose:** the number is required, at most 40 characters, and needs at least one letter
  or digit.
  - Search matches both the number as entered and the normalized key (`VehicleSpecifications.vehicleNumberContains`),
    so `dhk1234` finds `DHK-1234`.
- **Frontend:** `VehicleFormController` puts the 409's `errors[0]` (`field = vehicleNumber`), or the type
  `duplicate-vehicle-number`, under the number field of the form.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Only the database constraint | The client would get a generic error for the common case; the guideline wants a clear message on the field. |
| Only the service check | A lost update under concurrency: two inserts can both pass `exists` (guideline 12.1). |
| Store only the normalized number | Users would see `DHAKAMETROGA112345` instead of what they typed. |
| A functional unique index on an SQL expression | The normalization would live in SQL and in Java; an explicit column is simpler to query and to search. |
| Strict format validation (a regex for Bangladeshi plates) | Formats vary by region and vehicle class, so it would reject valid numbers. |

## Consequences

**Positive**
- TC-02, TC-03 and TC-04 are covered by:
  - `VehicleNumberTest` (normalization cases);
  - `VehicleServiceTest` (including "DB race → same 409");
  - `VehicleApiTest`, against the real constraint.
- The client gets the same 409 whichever check fires.
- The pattern carries over to driver phone and licence numbers in v2.0.

**Negative / accepted trade-offs**
- The constraint name and the message are defined twice: in the service and in `VehicleConstraintConfig`. The
  service matches the constraint with `message.contains(...)` on the JDBC driver's exception text.
- Separators other than spaces and hyphens are not treated as equal. For example, `DHK–1234` with an en dash, or a
  dot, counts as a different number.

**Follow-ups**
- Keep one mechanism for constraint races (0005).
- If real data shows other separators, extend `normalize` *and* add a migration that recomputes `number_key`. It
  must be a new migration, never an edit of V2.

## Related

- Guideline: sections 7.1, 12 (BR-01, BR-02, BR-03), 12.1, FR-V05, TC-02, TC-03, TC-04.
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0008](0008-error-format-problem-details.md).
- Files:
  - backend: `BE/vehicle/VehicleNumber.java`, `BE/vehicle/VehicleService.java`, `BE/vehicle/VehicleRepository.java`,
    `BE/vehicle/VehicleConstraintConfig.java`, `BE/vehicle/VehicleSpecifications.java`, `MIG/V2__create_vehicles.sql`;
  - frontend: `FE/features/vehicles/presentation/vehicle_form_controller.dart`;
  - contract: `docs/API_CONTRACT.md` §2 "Duplicate vehicle number".

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
