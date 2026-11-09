# 0011. Time and expiry: injected `Clock` in Asia/Dhaka, expiry status computed, not stored

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built — `Clock`
  for JWT, audit timestamps and the manufacture-year bound — and extended when v2.0 added expiry status, the
  maintenance date rule and the dashboard month)

## Context

Several rules depend on "now" or "today":
- token expiry (FR-AUTH-02);
- audit timestamps (FR-X01);
- the manufacture year "between 1980 and next year" (BR-03);
- document and licence expiry status (BR-08: EXPIRED if expiry < today, EXPIRING_SOON within 30 days, otherwise
  VALID, with "today" in Asia/Dhaka);
- "service date not in the future" (BR-09);
- "maintenance this month" on the dashboard (FR-DB01).

The Render server runs in UTC, and Dhaka is UTC+6, so between 00:00 and 06:00 Dhaka time the UTC date is still
yesterday. Guideline section 15 says to inject `java.time.Clock` and use `Clock.fixed(...)` in tests. The risk
table names "Timezone errors in expiry → wrong badges around midnight". Guideline 10.2 says "Expiry status is not
stored — it is calculated from expiry_date, so it can never go stale".

## Decision

- **One `Clock` bean:** `BE/config/ClockConfig.clock()` returns `Clock.system(ZoneId.of("Asia/Dhaka"))`.
  `ClockConfig.BUSINESS_ZONE` is the one constant for the business zone. No production code calls
  `LocalDate.now()`, `Instant.now()` or `Year.now()` without the clock.
- **Users of the clock:**
  - `JwtService` sets issued-at and expiry, and the parser checks expiry with the same clock.
  - `JpaAuditingConfig` provides a `DateTimeProvider` with `clock.instant()` truncated to microseconds, the
    `TIMESTAMPTZ` precision.
  - `ManufactureYearValidator` uses `Year.now(clock)`; it is created by Spring's validator factory, so the clock
    is injected.
  - `ValidationConfig` (v2.0) sets Bean Validation's `ClockProvider` to the same clock, so `@PastOrPresent` on
    `MaintenanceRequest.serviceDate` means "not after today in Dhaka".
  - `AssignmentService.now()` uses the clock truncated to microseconds, so a closed and a newly opened
    assignment share one timestamp.
- **Expiry status (v2.0):**
  - `BE/common/expiry/ExpiryCalculator` computes `today()` as `LocalDate.now(clock.withZone(BUSINESS_ZONE))`,
    whatever zone the clock has.
  - It returns `ExpiryInfo(status, daysUntilExpiry)`: `days < 0` → EXPIRED, `0 ≤ days ≤ 30` → EXPIRING_SOON,
    otherwise VALID (`EXPIRING_SOON_DAYS = 30`).
  - The calculator is shared by `DocumentMapper` (FR-D02) and `DriverMapper` (licences, FR-X02).
    `DocumentService.findExpiring` and `DashboardService` use the same `today()`.
  - The status is computed at read time and **never stored**: there is no status column in V3 or V5.
- **Storage of time:**
  - `hibernate.jdbc.time_zone: UTC`; timestamps are `TIMESTAMPTZ`, and business dates (`expiry_date`,
    `service_date`, `license_expiry`) are `DATE`.
  - JSON timestamps carry an offset; dates are plain ISO dates (`docs/API_CONTRACT.md` header).
- **Frontend:**
  - `FE/core/utils/clock.dart` has `Clock`/`FixedClock` behind `clockProvider`, for session expiry, the
    manufacture-year bound and date pickers.
  - `FE/core/utils/dates.dart` has `Dates.businessToday(now)`, which is UTC + 6 h (Dhaka has no DST); the
    maintenance form uses it.
  - `ExpiryStatus.compute` mirrors BR-08 only as a fallback when the API value is missing. The server value
    wins. That fallback reads `DateTime.now()` directly (`FE/shared/models/expiry_status.dart`), not `clockProvider`.
- **Tests:**
  - unit tests use `Clock.fixed(...)`; `ExpiryCalculatorTest` covers "today in Dhaka with a UTC clock",
    10 days, 0 days, 30 days, 31 days and yesterday;
  - `MaintenanceRequestValidationTest` checks that tomorrow in Dhaka is rejected and that today in Dhaka, which
    is still yesterday in UTC, is accepted;
  - API tests use dates relative to Dhaka "today".

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `LocalDate.now()` with the JVM default zone | Depends on the server (UTC on Render): badges would flip at 06:00 Dhaka time; tests would depend on the day they run. |
| Set the JVM / container time zone to Asia/Dhaka (`TZ`) | Works only where it is configured; tests and developer machines differ; still not testable with a fixed instant. |
| Store `expiry_status` and update it with a nightly job | Goes stale between runs; extra scheduler; guideline 10.2 says not to. |
| Compute the status in SQL (`CASE WHEN expiry_date < CURRENT_DATE ...`) | `CURRENT_DATE` uses the database server's time zone, not Dhaka; logic would live in SQL and Java. |
| Frontend computes the status from the browser date | The browser can be in any time zone; the server is the authority, the client only falls back. |

## Consequences

**Positive**
- Date rules are deterministic in tests and correct at midnight in Dhaka.
- One place to change the business zone or the 30-day window.

**Negative / accepted trade-offs**
- Every class that needs "now" must take a `Clock` (or `ExpiryCalculator`) in its constructor. A forgotten
  direct `now()` call is not caught by the build.
- The frontend date pickers for document and licence dates use the browser's calendar date
  (`ref.watch(clockProvider).now()`). Only the maintenance form uses `Dates.businessToday`. Around midnight in
  another time zone, the server may reject a date that the picker allowed; the server's field error is then shown
  (`stark-vms-frontend/README.md`, Known limitations; that README bullet also names the maintenance service
  date, although `maintenance_form_dialog.dart` uses `Dates.businessToday`).
- `ExpiryCalculator` (in `common`) imports `config.ClockConfig` for the zone constant.

**Follow-ups**
- Use `Dates.businessToday` in every date picker that is checked against a server rule.
- An architecture test that forbids `LocalDate.now()` / `Instant.now()` without arguments in `src/main`.

## Related

- Guideline: sections 3.3, 10.2, 12 (BR-03, BR-08, BR-09), 15 ("Make date tests deterministic"), 21 (risk
  table), FR-AUTH-02, FR-D02, FR-X01, FR-X02, FR-DB01, TC-08, TC-09.
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0018](0018-accepted-deviations-from-the-guideline.md) (BR-09 without DB CHECK).
- Files: `BE/config/ClockConfig.java`, `BE/config/ValidationConfig.java`, `BE/config/JpaAuditingConfig.java`,
  `BE/common/expiry/ExpiryCalculator.java`, `BE/common/validation/ManufactureYearValidator.java`,
  `BE/auth/JwtService.java`, `BE/assignment/AssignmentService.java`, `BE/maintenance/dto/MaintenanceRequest.java`,
  `FE/core/utils/clock.dart`, `FE/core/utils/dates.dart`, `FE/shared/models/expiry_status.dart`,
  `docs/API_CONTRACT.md` §6.1.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
