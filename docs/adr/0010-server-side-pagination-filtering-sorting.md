# 0010. Server-side pagination, search and sorting

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 built the vehicle list,
  FR-V01)

## Context

The performance NFR (guideline section 6) asks that list endpoints respond in under 500 ms for 1,000 vehicles and
"never load full tables into the browser".

The other requirements:
- FR-V01: the vehicle list comes from the database;
- TC-11: "Request page size 500 → capped at 100 (or 400)";
- guideline 11.1–11.2: list endpoints take `page`, `size` and `sort`, and return the wrapper `{content, page, size,
  totalElements, totalPages}`.

v2.0 (FR-V08, FR-V09) will add status/type filters and a search on brand, model and driver. v1.0 should not need a
rewrite for that. Spring Data's own `Page` JSON is not a stable contract.

## Decision

- **One page wrapper:** `BE/common/dto/PageResponse` is a record `(content, page, size, totalElements, totalPages)`
  built from a Spring `Page`. A Spring `Page` is never serialized directly.
- **One page-request builder:** `BE/common/util/PageRequests.of(page, size, sort, allowedFields, defaultSort)`.
  - The default size is 20 (`DEFAULT_PAGE_SIZE`), and a `size` above 100 is silently capped at 100
    (`MAX_PAGE_SIZE`, TC-11).
  - `page < 0` and `size < 1` give 400 through `@Min` on the controller parameters. A page whose offset would
    overflow `int` gives 400.
  - `sort` is `field,direction`, and the field must be in a whitelist that maps API names to entity properties.
    `VehicleSortField` allows `vehicleNumber`, `manufactureYear` and `createdAt`. An unknown field or direction
    gives 400.
  - The default is `createdAt,desc`, plus the tie-breaker `id desc` for a stable order.
- **Search is a JPA `Specification`.** `VehicleSpecifications.vehicleNumberContains` is a case-insensitive partial
  match on the number as entered **or** on the normalized key, escaped by `LikePatterns`.
  - In v1.0, search covers the vehicle number only (`GET /vehicles?search=`, `docs/API_CONTRACT.md` §2).
  - v2.0 adds its filters as further Specifications.
- **Frontend:**
  - `VehicleQuery` (page, size, sort, search) lives in the kept-alive `vehicleQueryProvider`;
  - `vehicleListProvider` watches it, and `shared/widgets/pagination_bar.dart` renders the paging;
  - search is debounced by 350 ms (`AppConfig.searchDebounce`, guideline 14.2).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Client-side paging and search (load everything) | Violates the NFR "never load full tables into the browser". |
| Return Spring's `Page` JSON | Not a stable contract. |
| Reject `size > 100` with 400 | Allowed by TC-11, but capping is friendlier to clients and still bounds the cost. |
| Free-form sort on any property | Clients could sort on internal columns or trigger errors on unknown properties. |
| Keyset (cursor) pagination | The UI needs page numbers and a total count, and offsets are fast enough at this size. |

## Consequences

**Positive**
- Measured with 1,000 vehicles (`docs/PERFORMANCE.md`, 2026-11-07): every list request has a p95 between 31 and
  92 ms via MockMvc, and between 36 and 90 ms over HTTP. `VehicleListPerformanceTest` asserts p95 < 500 ms in
  every build.
- A stable order means that a row never appears on two pages because two rows have equal sort keys.
- v2.0 extended this without changing the wrapper or the callers.

**Negative / accepted trade-offs**
- Each list runs a `count(*)` for `totalElements`.
- `%term%` search cannot use a B-tree index. This is fine at this size, and it will not stay fine at 100,000
  rows.

**Follow-ups** (only when the data grows)
- `pg_trgm` GIN indexes.
- A composite index `(created_at DESC, id DESC)`.
- Keyset paging for deep pages.

## Related

- Guideline: sections 6 (Performance), 11.1, 11.2, 14.2, FR-V01, TC-11.
- ADRs: [0009](0009-vehicle-number-normalization-and-duplicate-check.md), [0015](0015-non-functional-requirements-strategy.md).
- Files:
  - backend: `BE/common/dto/PageResponse.java`, `BE/common/util/PageRequests.java`,
    `BE/common/util/LikePatterns.java`, `BE/vehicle/VehicleSortField.java`, `BE/vehicle/VehicleSpecifications.java`,
    `BE/vehicle/VehicleController.java`;
  - frontend: `FE/features/vehicles/data/vehicle_query.dart`, `FE/core/network/page_response.dart`,
    `FE/shared/widgets/pagination_bar.dart`;
  - docs: `docs/API_CONTRACT.md` §2, `docs/PERFORMANCE.md`.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
