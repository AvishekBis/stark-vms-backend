# 0011. Time: one injected `Clock` in Asia/Dhaka

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built, for the JWT,
  the audit timestamps and the manufacture-year bound)

## Context

Several v1.0 rules depend on "now":
- token issue and expiry (FR-AUTH-02, about 60 minutes);
- the audit timestamps `createdAt` and `updatedAt` (FR-X01);
- the manufacture year "between 1980 and next year" (BR-03).

The Render server runs in UTC, and Dhaka is UTC+6, so "next year" changes at Dhaka's midnight on 31 December, not
at UTC's. Guideline section 15 says: inject `java.time.Clock` and use `Clock.fixed(...)` in tests. The risk table
names time-zone errors.

v2.0 will add expiry status with "today" in Asia/Dhaka (BR-08). The expiry part of this decision ("computed, not
stored") belongs to v2.0 and is recorded in Assignment 2's 0011.

## Decision

- **One `Clock` bean.** `BE/config/ClockConfig.clock()` returns `Clock.system(ZoneId.of("Asia/Dhaka"))`, and
  `ClockConfig.BUSINESS_ZONE` is the single constant for the business zone. No production code calls
  `LocalDate.now()`, `Instant.now()` or `Year.now()` without the clock.
- **Who uses it:**
  - `JwtService` sets issued-at and expiry, and the parser checks expiry against the same clock.
  - `JpaAuditingConfig` provides a `DateTimeProvider` that returns `clock.instant()` truncated to microseconds,
    which is the `TIMESTAMPTZ` precision. A response therefore shows exactly what was stored.
  - `ManufactureYearValidator` uses `Year.now(clock)`. Spring's validator factory creates it, so the clock is
    injected.
- **How time is stored:** `hibernate.jdbc.time_zone: UTC`, and the timestamps are `TIMESTAMPTZ`. JSON timestamps
  carry an offset (`docs/API_CONTRACT.md` header).
- **Frontend.** `FE/core/utils/clock.dart` has `Clock` and `FixedClock` behind `clockProvider`. `AuthController` uses
  it for session expiry, and the vehicle form uses it for the manufacture-year bound.
- **Tests:**
  - `ManufactureYearValidatorTest` checks the BR-03 bounds with a fixed Asia/Dhaka clock;
  - `JwtServiceTest` checks expiry with fixed clocks;
  - `auth_controller_test.dart` overrides `clockProvider` with a `FixedClock` for session expiry.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `Instant.now()` / `Year.now()` with the JVM default zone | Depends on the server (UTC on Render), and the tests would depend on the day they run. |
| Set the container time zone (`TZ=Asia/Dhaka`) | Works only where it is configured, and still cannot be tested with a fixed instant. |
| A `CHECK (manufacture_year <= EXTRACT(YEAR FROM CURRENT_DATE) + 1)` | `CURRENT_DATE` uses the database server's time zone, and a CHECK cannot use the injected clock. The DB keeps only the static lower bound `ck_vehicles_manufacture_year` (`>= 1980`). |

## Consequences

**Positive**
- Date logic is deterministic in tests.
- v2.0 could build the expiry rules (BR-08, BR-09) on the same bean without changing v1.0 code.

**Negative / accepted trade-offs**
- Every class that needs "now" must take a `Clock` in its constructor. A direct `now()` call that someone forgets
  is not caught by the build.
- The frontend clock is the browser's clock, so near the new year the year bound can differ between the browser and
  the server. The server's 400 field error then wins.

**Follow-ups**
- An architecture test that forbids calling `LocalDate.now()` or `Instant.now()` without arguments in `src/main`.

## Related

- Guideline: sections 12 (BR-03), 15 ("Make date tests deterministic"), 21 (risk table), FR-AUTH-02, FR-X01.
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0006](0006-postgresql-everywhere-flyway-and-validate.md),
  [0007](0007-authentication-stateless-jwt.md).
- Files: `BE/config/ClockConfig.java`, `BE/config/JpaAuditingConfig.java`, `BE/auth/JwtService.java`,
  `BE/common/validation/ManufactureYearValidator.java`, `FE/core/utils/clock.dart`,
  `FE/features/auth/presentation/auth_controller.dart`, `FE/features/vehicles/presentation/vehicle_form_screen.dart`.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
