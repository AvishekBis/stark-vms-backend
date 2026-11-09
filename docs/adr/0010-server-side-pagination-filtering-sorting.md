# 0010. Server-side pagination, filtering and sorting

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 built the vehicle
  list; v2.0 added status/type filters, brand/model/driver search, the driver list and the paged histories)

## Context

The performance NFR (guideline section 6) asks that list endpoints respond in under 500 ms for 1,000 vehicles and
"never load full tables into the browser". FR-V08 and FR-V09 require server-side search, filter (status, type),
sort (number, year, created date) and paging with a default size of 20, a maximum of 100 and a total count.
TC-11: "Request page size 500 → capped at 100 (or 400)". Guideline 11.1–11.2 define the page wrapper
`{content, page, size, totalElements, totalPages}`. Spring Data's own `Page` JSON is not a stable contract.

## Decision

- **One page wrapper:** `BE/common/dto/PageResponse` is a record `(content, page, size, totalElements,
  totalPages)` built from a Spring `Page`. A Spring `Page` is never serialized directly.
- **One page-request builder:** `BE/common/util/PageRequests.of(page, size, sort, allowedFields, defaultSort)`.
  - Default size is 20 (`DEFAULT_PAGE_SIZE`). `size` above 100 is silently capped at 100 (`MAX_PAGE_SIZE`,
    TC-11).
  - `page < 0` and `size < 1` give 400 through `@Min` on the controller parameters. A page whose offset would
    overflow `int` gives 400.
  - `sort` is `field,direction` and the field must be in a whitelist that maps API names to entity properties.
    An unknown field, an unknown direction or more than two parts give 400 with the allowed fields listed.
- **Whitelists and stable order:**
  - Vehicles (`VehicleSortField`): `vehicleNumber`, `manufactureYear`, `createdAt`. The default is
    `createdAt,desc`, with tie-breaker `id desc`.
  - Drivers (`DriverSortField`): `fullName`, `licenseExpiry`, `createdAt`. The default is `fullName,asc`, with
    tie-breaker `id asc`.
  - Histories use fixed orders: maintenance `serviceDate desc, id desc`, assignments `assignedAt desc, id desc`,
    expiring documents `expiryDate asc, id asc`.
- **Filters are JPA `Specification`s, AND-ed:**
  - `VehicleSpecifications`: search on number as entered, normalized key, brand, model, and current driver's
    name through a subquery; `status`; `type`.
  - `DriverSpecifications`: search on name, phone and licence; `status`; `available`.
  - User input in `LIKE` is escaped (`LikePatterns`). Every value is bound as a parameter.
- **Paged endpoints:**
  - `GET /vehicles`, `GET /drivers`, `GET /documents/expiring` (`withinDays` 0–365);
  - `GET /vehicles/{id}/maintenance` (`MaintenanceHistoryResponse` = page + `totalCost` + `recordCount` over all
    records);
  - `GET /vehicles/{id}/assignments`, `GET /drivers/{id}/assignments`.
  - `GET /vehicles/{id}/documents` is a bare array; see [0018](0018-accepted-deviations-from-the-guideline.md).
- **No N+1 queries:** the current drivers or vehicles of a page come from one `IN (...)` query
  (`AssignmentService.currentDriversByVehicleId` / `currentVehiclesByDriverId`). Repositories use `@EntityGraph`
  where a mapper needs an association.
- **Frontend:**
  - query state lives in kept-alive `NotifierProvider`s (`vehicleQueryProvider`, `driverQueryProvider`,
    `expiringDocumentsQueryProvider`);
  - the list `FutureProvider`s watch them, and `shared/widgets/pagination_bar.dart` renders paging;
  - search is debounced 350 ms (`AppConfig.searchDebounce`, guideline 14.2), and filters are URL query
    parameters.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Client-side paging/filtering (load everything) | Violates the NFR "never load full tables into the browser". |
| Return Spring's `Page` JSON | Not a stable contract (its shape changed between Spring Data versions). |
| Reject `size > 100` with 400 | Allowed by TC-11, but capping is friendlier for clients and still bounds the cost. |
| Free-form sort on any property | Clients could sort on unindexed or internal columns, or trigger errors on unknown properties; the whitelist makes the contract explicit. |
| Keyset (cursor) pagination | The UI needs page numbers and a total count; offsets are fast enough at this size (`docs/PERFORMANCE.md`). Kept as an option for very large fleets. |
| Querydsl / Criteria strings | Specifications are part of Spring Data and need no code generation. |

## Consequences

**Positive**
- With 1,000 vehicles, 300 drivers, about 3,000 documents and about 5,000 maintenance records, every list
  request has a p95 between 30 and 121 ms via MockMvc and between 39 and 114 ms over HTTP. `?size=1000`
  returns 100 rows (`docs/PERFORMANCE.md`, asserted in `ListEndpointsPerformanceTest`).
- Stable ordering means a row never appears on two pages because of equal sort keys.

**Negative / accepted trade-offs**
- Each list runs a `count(*)` for `totalElements`. The vehicle search count is a sequential scan (2.5 ms at
  1,000 rows).
- `%term%` searches cannot use B-tree indexes. This is fine at this size and will not stay fine at
  100,000 rows.
- The paging and sort logic on the frontend is duplicated across three query controllers (SOLID-FE-07, see 0004).

**Follow-ups** (only when the data grows; none is needed for the NFR):
- `pg_trgm` GIN indexes for contains-search;
- a composite index `(created_at DESC, id DESC)`;
- keyset pagination for deep pages.

## Related

- Guideline: sections 6 (Performance), 11.1, 11.2, 14.1, 14.2, FR-V01, FR-V08, FR-V09, FR-M02, TC-11.
- ADRs: [0015](0015-non-functional-requirements-strategy.md), [0018](0018-accepted-deviations-from-the-guideline.md).
- Files: `BE/common/dto/PageResponse.java`, `BE/common/util/PageRequests.java`, `BE/common/util/LikePatterns.java`,
  `BE/vehicle/VehicleSortField.java`, `BE/vehicle/VehicleSpecifications.java`, `BE/driver/DriverSortField.java`,
  `BE/driver/DriverSpecifications.java`, `BE/assignment/AssignmentService.java`, `FE/core/network/page_response.dart`,
  `FE/shared/widgets/pagination_bar.dart`, `docs/API_CONTRACT.md` §2, §6.2–§6.6, `docs/PERFORMANCE.md`.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
