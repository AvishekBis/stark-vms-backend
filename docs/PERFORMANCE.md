# Performance — v1.0 (guideline section 6)

> **NFR:** "List endpoints respond in under 500 ms for 1,000 vehicles (excluding Render cold start). Never load
> full tables into the browser."

**Verdict: met.** With 1,000 vehicles, every list request has a p95 between **31 and 92 ms** through MockMvc and
between **36 and 90 ms** over real HTTP. The slowest p95 uses less than a fifth of the 500 ms budget. No query had
to be optimised, so no migration was added.

Measured on 2026-11-07.

## How it is measured

| | Automated test (`./mvnw verify`) | End-to-end over HTTP (once, by hand) |
|---|---|---|
| Where | [`VehicleListPerformanceTest`](../src/test/java/com/starkgroup/vms/vehicle/VehicleListPerformanceTest.java) | `./mvnw spring-boot:test-run` (port 8080), timed with `curl -w '%{time_total}'` |
| Path timed | MockMvc: JWT filter → controller → service → SQL → JSON | Real Tomcat + TCP on localhost, gzip on (`curl --compressed`, like a browser) |
| Database | Its own embedded PostgreSQL 17.5 with a HikariCP pool of 5 (production default `DB_POOL_SIZE`) | Embedded PostgreSQL 17.5 from `TestVmsApplication`, HikariCP pool of 5 |
| Dataset | 1,000 vehicles in one JDBC batch, then `ANALYZE` | 26 demo vehicles plus 974 inserted over JDBC = 1,000 |
| Runs | 50 global JIT warm-up requests, then for each request 5 warm-up + 20 timed runs | Login for a JWT, then for each request 5 warm-up + 20 timed runs |
| Pass rule | `assert p95 < 500 ms` for every request; the test fails otherwise | Same rule, checked by hand |

The 1,000 rows are realistic. Plates look like `CHATTA METRO-GHA 12-0001`, with 8 vehicle types, 10 brands,
7 fuel types, years 2005–2025, status 80 % ACTIVE / 10 % MAINTENANCE / 10 % INACTIVE, and `createdAt` spread
over 1,000 minutes. Each request is also checked for correct content, for example `totalElements = 1000`,
exactly one hit for `-0500`, or `manufactureYear = 2025` on top. This proves that the timed query really ran
against the data.

The test runs in **its own Spring context with its own database**, so its rows cannot leak into other tests.
The test class takes about 35 s inside `./mvnw verify`, context start included.

### Why the test has its own pooled database

The other API tests use zonky's `@AutoConfigureEmbeddedDatabase`. Its DataSource **opens a new PostgreSQL
connection for every transaction**. On Windows each new connection starts a new PostgreSQL backend process and
costs about 85–140 ms. A `SELECT 1` took 142 ms that way and 0.9 ms on a pooled connection. The first attempt,
inside the shared test context, measured about 200 ms per list request. Almost all of that time was spent
opening the connection, not running the query. Production uses HikariCP, so the performance test does too.

## Environment

| | |
|---|---|
| Machine | Windows 11 Home, Intel Core i5-13420H (12 threads), 7.7 GB RAM, laptop |
| Load | Not idle: other builds and tests ran in parallel (about 50 % CPU load measured). Treat the numbers as pessimistic. |
| Runtime | Temurin JDK 21.0.13, Spring Boot 3.5.6, Hibernate 6, embedded PostgreSQL 17.5 (zonky binaries) |
| Test JVM | Runs with the JaCoCo coverage agent (`./mvnw verify`), which makes it slightly slower than production |

## Results (ms, 1,000 vehicles)

| Request | MockMvc median | MockMvc p95 | HTTP median | HTTP p95 | < 500 ms |
|---|---:|---:|---:|---:|:---:|
| Default list `GET /api/v1/vehicles` (page 0, size 20, `createdAt,desc`) | 37.6 | 61.2 | 46.2 | 57.2 | yes |
| Deep page `?page=49` (rows 981–1000) | 32.6 | 39.4 | 47.7 | 59.5 | yes |
| Max page size `?size=100` | 66.9 | 91.7 | 41.4 | 83.3 | yes |
| Search number, one hit `?search=-0500` | 33.0 | 51.1 | 31.7 | 49.2 | yes |
| Search number, normalized `?search=dhakametroga` | 45.4 | 64.9 | 43.6 | 80.4 | yes |
| Search number, broad `?search=METRO` (> 500 hits) | 41.1 | 52.9 | 34.3 | 90.2 | yes |
| Sort `?sort=manufactureYear,desc` | 25.1 | 30.5 | 29.3 | 36.0 | yes |
| Sort `?sort=vehicleNumber,asc&page=25` | 36.1 | 41.6 | 37.5 | 59.1 | yes |

- The highest single run in each series was 104 ms for MockMvc and 184 ms for HTTP.
- For comparison, `GET /actuator/health` (no list) takes about 20–30 ms over HTTP on this machine.
- Over HTTP, gzipped response bodies are 0.25–1.1 KB per page of 20 and 3.5 KB for a page of 100.
- Search by brand, the status / type filters and sort combinations are v2.0 features (FR-V08 / FR-V09). In
  v1.0, `search` matches the vehicle number only, so the brand row is measured in Assignment 2.

### Where the time goes

`EXPLAIN (ANALYZE)` on the 1,000-row table:

| Query | Plan | Execution |
|---|---|---:|
| Page 49: `ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 980` | Seq Scan + in-memory quicksort, 15 shared buffers | 1.5 ms |
| Broad search `lower(vehicle_number) LIKE '%metro%' OR lower(number_key) LIKE …` | Seq Scan + top-N heapsort | 4.5 ms |
| `count(*)` for `totalElements` | Seq Scan | ≈ 1.5 ms |

The SQL takes at most about 5 ms. The rest of each request is framework work (Hibernate mapping, Jackson,
security filters), and it was inflated here by the shared, loaded machine.

## "Never load full tables into the browser"

- Every list is paged on the server. The default size is 20 and `size` is capped at 100 (`PageRequests`, TC-11).
- Checked over HTTP: `GET /api/v1/vehicles?size=1000` returns 100 rows, with `size = 100`,
  `totalElements = 1000` and `totalPages = 10`.
- Search, sorting and the total count run in PostgreSQL. The Flutter list only ever holds the current page.

## Optimisation

None was needed. The slowest p95 (92 ms) is far below 500 ms, and at this size PostgreSQL correctly prefers a
sequential scan. The existing indexes `idx_vehicles_created_at` and `idx_vehicles_status` (V2) are enough. No
migration was changed or added.

Options if the fleet grows by orders of magnitude (not needed for the NFR):

- a composite index `(created_at DESC, id DESC)`, so the default order with its tie-breaker can stop after 20 rows;
- a `pg_trgm` GIN index for the "contains" search;
- keyset pagination instead of `OFFSET` for very deep pages.

## Reproduce

```bash
cd stark-vms-backend
./mvnw -B test -Dtest=VehicleListPerformanceTest     # prints the median / p95 / max table in the log
```

For the HTTP run, start `./mvnw spring-boot:test-run`. Top the database up to 1,000 vehicles, either through
`POST /api/v1/vehicles` or with SQL on `localhost:5433`. Then time each request with
`curl -s -o /dev/null --compressed -H "Authorization: Bearer $TOKEN" -w '%{time_total}' "http://localhost:8080/api/v1/vehicles?page=49"`.
If port 8080 is busy, add `-Dspring-boot.run.arguments=--server.port=8090`.
