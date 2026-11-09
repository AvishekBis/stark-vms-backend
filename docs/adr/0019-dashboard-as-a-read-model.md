# 0019. Dashboard as a read model: one summary endpoint, aggregate queries, one snapshot

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v2.0 built FR-DB01 and
  FR-X02; the REPEATABLE READ isolation was added on 2026-11-07)
- **New in v2.0** (v1.0 had no dashboard endpoint)

## Context

FR-DB01 asks for these numbers, which must "match the database":
- total, active, inactive and under-maintenance vehicles;
- documents that are expired and expiring soon;
- the maintenance count and cost this month.

FR-X02 adds driver licence expiry. The guideline catalogue (11.3) has `GET /dashboard/summary`, "All dashboard
numbers in one call". The performance NFR forbids loading whole tables. The numbers come from five contexts:
vehicle, document, maintenance, driver and assignment.

In v1.0 the frontend built its small dashboard from `GET /vehicles` calls: `size=1` for the total and the five
newest vehicles (`assignment 1/stark-vms-frontend/lib/features/dashboard/presentation/dashboard_providers.dart`).
That does not scale to counts by status, documents and costs.

## Decision

- **One endpoint, one service method:** `GET /api/v1/dashboard/summary` → `BE/dashboard/DashboardService.summary()`
  → `DashboardSummaryResponse`, with the parts `vehicles`, `documents`, `maintenance`, `drivers`,
  `expiringDocuments` (top 10), `expiringLicenses` (top 10) and `generatedAt` (contract §6.7).
- **`dashboard` is a read-only context with no table.** It is the one context allowed to read other contexts'
  repositories directly, and only for aggregates ([0003](0003-data-ownership-and-cross-context-contracts.md)):
  - `VehicleRepository.countByStatus()` (one GROUP BY);
  - `VehicleDocumentRepository.countByExpiryDateBefore/Between`;
  - `MaintenanceRecordRepository.countByServiceDateBetween` and `sumCostByServiceDateBetween`;
  - `DriverRepository.count`, `countByStatus`, `countByLicenseExpiryBefore/Between`;
  - `VehicleDriverAssignmentRepository.countByUnassignedAtIsNull`.
  For the two lists it calls the owners' services, `DocumentService.findExpiring(30, 0, 10)` and
  `DriverService.findExpiringLicenses(10)`, so the expiry status is mapped by the owners.
- **Dates:**
  - "Today" and the 30-day window come from `ExpiryCalculator` (Asia/Dhaka);
  - "this month" is `YearMonth.from(today)`, so the month boundary is Dhaka's
    ([0011](0011-time-and-expiry-with-injected-clock.md)).
- **Consistency:** `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`. In PostgreSQL every
  statement under READ COMMITTED gets its own snapshot. Under REPEATABLE READ all of the queries see one
  snapshot, so for example `vehicles.total` equals the sum of the three status counts even while another user
  saves.
- **Frontend:**
  - `dashboardSummaryProvider` (`FutureProvider.autoDispose`) watches all four change counters, so the
    dashboard refetches after any mutation ([0004](0004-frontend-state-management-with-riverpod.md));
  - `DashboardSummary.fromJson` reuses the `VehicleDocument` and `Driver` models for the two lists.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Keep computing the dashboard in the frontend from list endpoints (as v1.0 did) | Several requests per load, and it cannot count by status, sum costs or count expiring documents without loading pages of rows. |
| One endpoint per card (`/vehicles/count`, `/documents/expiring/count`, …) | The guideline asks for one call. Several calls also take several snapshots, so the numbers could disagree. |
| Each count through the owning service (no repository access from `dashboard`) | About ten pass-through service methods that exist only for the dashboard. Accepted instead: read-only repository access from this one context, recorded in 0003. |
| Materialized view or a cached summary table | Stale between refreshes (FR-DB01 "numbers match the database") and an extra refresh job. At 1,000 vehicles the live queries take 59–82 ms median (`docs/PERFORMANCE.md`). |
| READ COMMITTED (the default) | Each count could see a different committed state. Used until 2026-11-07, changed to REPEATABLE READ (finding NFR-REL-05). |

## Consequences

**Positive**
- One request per dashboard load. All numbers are from one snapshot and computed in SQL.
- Measured: p95 121 ms (MockMvc) and 72 ms (HTTP) with the full performance dataset, which makes it the slowest
  endpoint but still far within 500 ms.
- Covered by:
  - `DashboardServiceTest` (4 tests: GROUP BY totals, missing statuses as 0, the Dhaka month at a UTC month
    boundary);
  - `DashboardApiTest` (3 tests: TC-12 exact counts and cost for known data, an empty database, and seeded demo
    data equal to independent SQL counts).

**Negative / accepted trade-offs**
- `dashboard` couples to five repositories. A renamed column or a changed rule (for example "what counts as
  assigned") in another context can break or silently change it; the tests above are the guard.
- `DashboardSummaryResponse` embeds `DocumentResponse` and `DriverResponse`, so a change to those DTOs changes
  the dashboard contract too.
- The frontend dashboard navigates to the drivers list by changing the drivers query controller instead of
  through the URL (SOLID-FE-12, see 0003).

**Follow-ups**
- If the dashboard grows, owner-provided "summary" ports (one per context) instead of direct repository access.
- Dashboard → drivers navigation through query parameters (SOLID-FE-12).

## Related

- Guideline: sections 6 (Performance), 11.3, FR-DB01, FR-X02, TC-12.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0004](0004-frontend-state-management-with-riverpod.md),
  [0010](0010-server-side-pagination-filtering-sorting.md), [0011](0011-time-and-expiry-with-injected-clock.md),
  [0015](0015-non-functional-requirements-strategy.md).
- Files: `BE/dashboard/DashboardService.java`, `BE/dashboard/DashboardController.java`,
  `BE/dashboard/dto/DashboardSummaryResponse.java`, `FE/features/dashboard/`, `docs/API_CONTRACT.md` §6.7,
  `docs/PERFORMANCE.md`.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
