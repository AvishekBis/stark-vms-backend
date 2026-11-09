# 0006. PostgreSQL everywhere, Flyway migrations and `ddl-auto=validate`

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; v2.0
  added migrations V3–V6 under the same rule)

## Context

Production runs on Render's managed PostgreSQL (guideline 18.3). v2.0 needs PostgreSQL-only features: partial
unique indexes for "one current assignment per vehicle and per driver" (BR-06, guideline 10.3), `TIMESTAMPTZ`,
`BYTEA`, `GENERATED ALWAYS AS IDENTITY`. Guideline risk table (section 21): "Different databases locally and in
production → migrations fail in production; mitigation: PostgreSQL everywhere". Section 10.3 asks for Flyway and
`ddl-auto=validate`. Section 15 asks for API tests that exercise "real SQL constraints", with "Testcontainers
PostgreSQL (or a test database)". The development machine is Windows without Docker, and the CI runner must not
need a database service either.

## Decision

1. **PostgreSQL is the only database**, locally, in tests, in CI and on Render. There is no H2 and no other
   dialect.
2. **Flyway owns the schema.**
   - Migrations are in `stark-vms-backend/src/main/resources/db/migration/`: `V1__create_users.sql`,
     `V2__create_vehicles.sql`, then v2.0 `V3__create_drivers.sql`, `V4__create_assignments.sql`,
     `V5__create_documents.sql`, `V6__create_maintenance.sql`.
   - A migration that has run is never edited. A change is a new file (`docs/API_CONTRACT.md` §6.9: "new
     migrations only — never edit V1/V2").
3. **Hibernate only validates.** `application.yml` sets:
   - `spring.jpa.hibernate.ddl-auto: validate`;
   - `spring.jpa.open-in-view: false`;
   - `hibernate.jdbc.time_zone: UTC`;
   - a Hikari pool of `DB_POOL_SIZE` (default 5).
4. **Schema conventions** (guideline 10.2), visible in V1–V6:
   - plural snake_case tables, `BIGINT GENERATED ALWAYS AS IDENTITY` keys;
   - `created_at`/`updated_at` `TIMESTAMPTZ` and `created_by`;
   - enum columns as `VARCHAR` with `CHECK`, and `NUMERIC(12,2)` for money (`maintenance_records.cost`);
   - named constraints (`uk_*`, `ck_*`, `fk_*`, `uq_*`, `idx_*`), `ON DELETE RESTRICT` on every FK.
5. **Tests use an embedded real PostgreSQL (zonky), not Testcontainers.**
   - Artifacts: `io.zonky.test:embedded-database-spring-test` 2.6.0, `embedded-postgres` 2.1.0, PostgreSQL
     binaries 17.5.0 (`pom.xml`).
   - Every `*ApiTest` class and `ListEndpointsPerformanceTest` use
     `@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)`, and Flyway runs the real migrations.
   - `TestVmsApplication` + `LocalEmbeddedPostgresConfig` (in `src/test`) give `./mvnw spring-boot:test-run`, a
     local backend with no PostgreSQL install.
   - The performance test runs in its own Spring context with a HikariCP pool. zonky's DataSource opens a new
     connection per transaction, which on Windows costs 85–140 ms (`docs/PERFORMANCE.md`).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| H2 (or another in-memory DB) for tests | Different SQL dialect; no partial unique indexes as PostgreSQL has them; the guideline risk table names exactly this risk. |
| `ddl-auto=update` / `create` | Hibernate would change the production schema silently; no reviewable history. Against guideline 10.3. |
| Liquibase | Equivalent capability; Flyway is what the guideline names and plain SQL files are easier to review. |
| Testcontainers PostgreSQL | Named by the guideline (section 15 and the risk table). It needs a Docker daemon: the Windows development machine has none, and CI would need Docker-in-runner. zonky starts a real PostgreSQL 17.5 binary in-process, which gives the same SQL behaviour without Docker. Recorded as a deviation in [0018](0018-accepted-deviations-from-the-guideline.md). |
| A shared developer/CI PostgreSQL server | Tests would depend on network and shared state; not reproducible. |
| MySQL | No partial unique indexes (guideline 10.3 says so explicitly). |

## Consequences

**Positive**
- The real constraints are exercised in tests, so the database safety nets are proven, not assumed:
  - `uk_vehicles_number_key`, `uk_drivers_phone`, `uk_drivers_license_key`;
  - `uq_assignment_open_vehicle` and `uq_assignment_open_driver`;
  - `ck_vehicle_documents_dates`, `ck_maintenance_cost` and the RESTRICT FKs.
- A schema that does not match the entities fails at start-up, not at the first query.
- `mvn -B verify` in CI needs only a JDK.

**Negative / accepted trade-offs**
- zonky downloads its PostgreSQL binaries as a Maven dependency (a large artifact in the local repository).
- The embedded binary version (17.5) is not pinned to Render's server version; `render.yaml` does not pin a
  PostgreSQL major version.
- Without `open-in-view`, every lazy association that a mapper needs must be fetched inside the service
  transaction (`@EntityGraph`, join fetch). A missing fetch shows up as a `LazyInitializationException` in tests.
- The free Render PostgreSQL has a 1 GB limit, which matters for document bytes ([0013](0013-document-file-storage-behind-storageservice.md)).

**Follow-ups**
- Pin the Render PostgreSQL major version (and match the zonky binaries to it).
- Consider Testcontainers in CI only, if Docker becomes available, to test against the exact production version.

## Related

- Guideline: sections 8 (Database, Migrations), 10.1–10.3, 15, 18.3, 21 (risk table).
- ADRs: [0014](0014-driver-vehicle-assignment-model.md), [0018](0018-accepted-deviations-from-the-guideline.md).
- Files: `stark-vms-backend/src/main/resources/application.yml`, `stark-vms-backend/src/main/resources/db/migration/V1`–`V6`,
  `stark-vms-backend/pom.xml`, `stark-vms-backend/src/test/java/com/starkgroup/vms/TestVmsApplication.java`,
  `.../LocalEmbeddedPostgresConfig.java`, `.../support/ApiTestSupport.java`, `docs/ER_DIAGRAM.md`, `docs/PERFORMANCE.md`.
