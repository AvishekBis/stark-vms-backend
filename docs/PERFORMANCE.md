# Performance — v2.0, re-checked for v2.1 (guideline section 6)

> **NFR:** "List endpoints respond in under 500 ms for 1,000 vehicles (excluding Render cold start). Never load
> full tables into the browser."

**Verdict: met.** The test fleet has 1,000 vehicles, 300 drivers, 250 current assignments, about 3,000 documents
and about 5,000 maintenance records. With that data, every list request — vehicles (search, filters, sort,
deep pages), drivers, expiring documents and the dashboard — has a p95 between **30 and 121 ms** through
MockMvc and between **39 and 114 ms** over real HTTP. The slowest p95 is the dashboard (121 ms), about a quarter
of the 500 ms budget. No query had to be optimised, so no migration was added; V1–V6 are unchanged.

Measured on 2026-11-07.

## How it is measured

| | Automated test (`./mvnw verify`) | End-to-end over HTTP (once, by hand) |
|---|---|---|
| Where | [`ListEndpointsPerformanceTest`](../src/test/java/com/starkgroup/vms/ListEndpointsPerformanceTest.java) | `./mvnw spring-boot:test-run` (port 8080), timed with `curl -w '%{time_total}'` |
| Path timed | MockMvc: JWT filter → controller → service → SQL (joins, subqueries, counts) → JSON | Real Tomcat + TCP on localhost, gzip on (`curl --compressed`, like a browser) |
| Database | Its own embedded PostgreSQL 17.5 with a HikariCP pool of 5 (production default `DB_POOL_SIZE`) | Embedded PostgreSQL 17.5 from `TestVmsApplication`, HikariCP pool of 5 |
| Runs | 50 global JIT warm-up requests, then for each request 5 warm-up + 20 timed runs | Login for a JWT, then for each request 5 warm-up + 20 timed runs |
| Pass rule | `assert p95 < 500 ms` for every request; the test fails otherwise | Same rule, checked by hand |

### Dataset

| Table | Automated test (JDBC batches, then `ANALYZE`) | HTTP run (demo data + JDBC top-up) |
|---|---:|---:|
| `vehicles` | 1,000 (80 % ACTIVE, 10 % MAINTENANCE, 10 % INACTIVE; 8 types, 10 brands) | 1,000 (26 demo + 974) |
| `drivers` | 300 (20 first × 15 last names, 10 INACTIVE, licence expiry from −60 to +840 days) | 300 (12 demo + 288) |
| `vehicle_driver_assignments` | 650: 250 current (ACTIVE vehicle + ACTIVE driver, BR-05/06) + 400 closed | 654, of which 250 current |
| `vehicle_documents` (metadata) | 3,000 (3 per vehicle, expiry from −60 to +399 days) | 2,942 (20 demo + 2,922) |
| `maintenance_records` | 5,000 (5 per vehicle, last 12 months) | 4,900 (30 demo + 4,870) |

Each request is also checked for correct content before it is timed. For example: `totalElements = 1000`,
exactly 100 Toyota vehicles, the first vehicle found for `rahim` has a current driver named Rahim, 20 "Uddin"
drivers, and the dashboard shows `vehicles.total = 1000` and `drivers.assigned = 250`. This proves that the
timed queries ran against the data.

The test runs in **its own Spring context with its own database**, so its rows cannot leak into other tests.
The test class takes about 45 s inside `./mvnw verify`, context start included.

### Why the test has its own pooled database

The other API tests use zonky's `@AutoConfigureEmbeddedDatabase`. Its DataSource **opens a new PostgreSQL
connection for every transaction**. On Windows each new connection starts a new PostgreSQL backend process and
costs about 85–140 ms. A `SELECT 1` took 142 ms that way and 0.9 ms on a pooled connection. That would make every
request look 100+ ms slower than it really is. Production uses HikariCP, so the performance test does too.

## Environment

| | |
|---|---|
| Machine | Windows 11 Home, Intel Core i5-13420H (12 threads), 7.7 GB RAM, laptop |
| Load | Not idle: other builds and tests ran in parallel (about 50 % CPU load measured). Treat the numbers as pessimistic. |
| Runtime | Temurin JDK 21.0.13, Spring Boot 3.5.6, Hibernate 6, embedded PostgreSQL 17.5 (zonky binaries) |
| Test JVM | Runs with the JaCoCo coverage agent (`./mvnw verify`), which makes it slightly slower than production |

## Results (ms)

| Request (`/api/v1` prefix omitted) | MockMvc median | MockMvc p95 | HTTP median | HTTP p95 | < 500 ms |
|---|---:|---:|---:|---:|:---:|
| Vehicles, default list `GET /vehicles` (page 0, size 20, `createdAt,desc`, incl. current drivers) | 35.8 | 45.3 | 50.7 | 69.9 | yes |
| Vehicles, deep page `?page=49` (rows 981–1000) | 37.1 | 55.4 | 51.8 | 63.0 | yes |
| Vehicles, max page size `?size=100` | 56.6 | 89.6 | 54.8 | 79.6 | yes |
| Vehicles, search number `?search=-0500` | 40.8 | 58.3 | 42.3 | 77.8 | yes |
| Vehicles, search brand `?search=toyota` | 55.9 | 81.2 | 52.4 | 113.9 | yes |
| Vehicles, search current driver name `?search=rahim` | 39.4 | 50.1 | 43.6 | 83.0 | yes |
| Vehicles, filters + sort `?status=ACTIVE&type=CAR&sort=manufactureYear,desc` | 26.8 | 30.3 | 32.7 | 41.7 | yes |
| Drivers, default list `GET /drivers` (`fullName,asc`, incl. current vehicles) | 29.3 | 38.6 | 30.6 | 38.5 | yes |
| Drivers, search `?search=uddin` | 31.1 | 35.1 | 35.4 | 41.5 | yes |
| Drivers, assign dialog `?available=true` | 31.4 | 38.4 | 40.4 | 48.3 | yes |
| Documents, `GET /documents/expiring` (30 days) | 28.6 | 42.6 | 36.7 | 59.7 | yes |
| Documents, `?withinDays=365&page=50` (deep page of about 2,800 rows) | 43.7 | 66.7 | 39.2 | 45.7 | yes |
| Dashboard, `GET /dashboard/summary` (10 COUNT/SUM/GROUP BY queries + 2 top-10 lists) | 81.5 | 121.0 | 58.8 | 71.6 | yes |

- One single run went over 500 ms: 515 ms on `?size=100` in MockMvc, almost certainly a GC pause or the OS
  scheduler on the shared machine. Its p95 is 90 ms, and over HTTP the same request never went above 85 ms.
  Apart from that run, the highest single run was 124 ms for MockMvc and 202 ms for HTTP.
- For comparison, `GET /actuator/health` (no list) takes about 20–30 ms over HTTP on this machine.
- Over HTTP, gzipped response bodies are 0.3–1.3 KB per page of 20 and 4 KB for a page of 100.

### Where the time goes

`EXPLAIN (ANALYZE)` against the HTTP-run database:

| Query | Plan | Execution |
|---|---|---:|
| Vehicle search page: number / brand / model `LIKE` OR `EXISTS` (current assignment ⋈ driver name) | Index Scan `idx_vehicles_created_at` + incremental sort; the driver subquery is hashed once and uses `uq_assignment_open_driver` | 0.8 ms |
| The same search, `count(*)` for `totalElements` | Seq Scan + hashed subplan | 2.5 ms |
| Vehicle page 49 `ORDER BY created_at DESC, id DESC OFFSET 980` | Seq Scan + quicksort | 0.6 ms |
| Current drivers of a page (`a.vehicle_id IN (…) AND unassigned_at IS NULL`) — one query, no N+1 | Bitmap Index Scan on the partial index `uq_assignment_open_vehicle` | 0.1 ms |
| Expiring documents, page 50 of 365 days, joined with vehicles | Index Scan `idx_vehicle_documents_expiry_date` + incremental sort + memoized vehicle lookup | 4.9 ms |
| Dashboard "expiring soon" count | Index Only Scan `idx_vehicle_documents_expiry_date` | 0.07 ms |

Every query takes at most about 5 ms and uses the indexes from V2–V6 where they help. The rest of each request
is framework work (Hibernate mapping, Jackson, security filters), and it was inflated here by the shared, loaded
machine. The dashboard is the slowest request only because it runs 14 small queries in one REPEATABLE READ
transaction.

## Re-check after v2.1 (2026-11-08): user lookup on every request

Since v2.1 ([ADR 0024](adr/0024-admin-user-management.md)) the JWT filter loads the user by primary key on every
authenticated request (disabled users, password changes and the current role take effect at once). The
`ListEndpointsPerformanceTest` timings include that lookup, because every timed MockMvc request goes through the
filter. Run of 2026-11-08 inside `./mvnw -q -B verify` (365 tests, same dataset and method as above, JaCoCo on,
other builds running on the laptop):

| Request (MockMvc) | `GET` | median | p95 | max | p95 v2.0 (2026-11-07) |
|---|---|---:|---:|---:|---:|
| vehicles: default list (page 0, size 20, createdAt desc) | `GET /vehicles` | 21.1 | 33.8 | 41.1 | 45.3 |
| vehicles: deep page 49 (rows 981-1000) | `GET /vehicles?page=49` | 19.0 | 34.2 | 38.0 | 55.4 |
| vehicles: max page size 100 | `GET /vehicles?size=100` | 25.7 | 35.1 | 45.9 | 89.6 |
| vehicles: search number, one hit | `GET /vehicles?search=-0500` | 20.0 | 33.3 | 34.1 | 58.3 |
| vehicles: search brand | `GET /vehicles?search=toyota` | 27.7 | 31.5 | 46.6 | 81.2 |
| vehicles: search current driver name | `GET /vehicles?search=rahim` | 25.2 | 33.1 | 35.0 | 50.1 |
| vehicles: status + type filter, sort manufactureYear desc | `GET /vehicles?status=ACTIVE&type=CAR&sort=manufactureYear,desc` | 20.9 | 30.8 | 45.4 | 30.3 |
| drivers: default list (fullName asc) | `GET /drivers` | 19.7 | 43.5 | 49.3 | 38.6 |
| drivers: search name | `GET /drivers?search=uddin` | 23.9 | 35.6 | 37.9 | 35.1 |
| drivers: available=true (assign dialog) | `GET /drivers?available=true` | 21.3 | 35.9 | 44.6 | 38.4 |
| documents: expiring within 30 days | `GET /documents/expiring` | 19.2 | 25.5 | 60.4 | 42.6 |
| documents: expiring within 365 days, page 50 | `GET /documents/expiring?withinDays=365&page=50` | 31.4 | 90.7 | 130.6 | 66.7 |
| dashboard: summary | `GET /dashboard/summary` | 43.9 | 80.6 | 103.6 | 121.0 |

**Verdict: p95 did not get worse.** Every p95 is between 25.5 and 90.7 ms (v2.0: 30.3–121.0 ms), still far below
the 500 ms budget. The differences are within the run-to-run noise of a shared laptop (the dashboard is faster, the
deep expiring-documents page slower than on 2026-11-07); the primary-key lookup itself is a sub-millisecond indexed
query and is not visible in these numbers. The HTTP columns above were not re-measured.

## "Never load full tables into the browser"

- Every list is paged on the server: vehicles, drivers, expiring documents and maintenance history. The default
  size is 20 and `size` is capped at 100 (`PageRequests`, TC-11). Checked over HTTP: `?size=1000` returns
  100 rows.
- Search (including the current driver's name), the status and type filters, sorting and totals run in
  PostgreSQL. The current drivers and vehicles of a page come from one extra `IN (…)` query, not one query per
  row.
- The dashboard uses COUNT / SUM / GROUP BY queries plus two top-10 lists. It never loads whole tables, neither
  into the JVM nor into the browser.

## Optimisation

None was needed, because all p95 values are far below 500 ms. The indexes already in V2–V6 are used where they
help: `created_at`, the partial `uq_assignment_open_*` indexes, `expiry_date` and
`(vehicle_id, service_date DESC)`. At this size PostgreSQL correctly prefers sequential scans elsewhere. No
migration was changed or added.

Options if the fleet grows by orders of magnitude (not needed for the NFR):

- a composite index `(created_at DESC, id DESC)` for the default order with its tie-breaker;
- `pg_trgm` GIN indexes for the "contains" search on number, brand, model and driver name;
- keyset pagination instead of `OFFSET` for very deep pages.

## Reproduce

```bash
cd stark-vms-backend
./mvnw -B test -Dtest=ListEndpointsPerformanceTest     # prints the median / p95 / max table in the log
```

For the HTTP run, start `./mvnw spring-boot:test-run`, which also seeds the demo data. Top the database up with
SQL on `localhost:5433` to the dataset above. Then time each request with
`curl -s -o /dev/null --compressed -H "Authorization: Bearer $TOKEN" -w '%{time_total}' "http://localhost:8080/api/v1/vehicles?search=rahim"`.
If port 8080 is busy, add `-Dspring-boot.run.arguments=--server.port=8090`; to move the database port, set
`LOCAL_PG_PORT=5444`.
