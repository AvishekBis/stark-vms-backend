# 0006. PostgreSQL everywhere, Flyway migrations and `ddl-auto=validate`

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built)

## Context

- Production runs on Render's managed PostgreSQL (guideline 18.3).
- The guideline risk table (section 21) says: "Different databases locally and in production → migrations fail in
  production; mitigation: PostgreSQL everywhere".
- Section 10.3 asks for Flyway and `ddl-auto=validate`.
- Section 10.3 also notes that v2.0 needs partial unique indexes, a PostgreSQL feature, so v1.0 should not choose a
  database that v2.0 has to replace.
- Section 15 asks for API tests that exercise "real SQL constraints", with "Testcontainers PostgreSQL (or a test
  database)".
- The development machine is Windows without Docker, and the CI runner must not need a database service either.

## Decision

1. **PostgreSQL is the only database**, locally, in tests, in CI and on Render. There is no H2 and no other
   dialect.
2. **Flyway owns the schema.**
   - The migrations are `stark-vms-backend/src/main/resources/db/migration/V1__create_users.sql` and
     `V2__create_vehicles.sql`.
   - A migration that has run is never edited; a change is a new file.
3. **Hibernate only validates.** `application.yml` sets:
   - `spring.jpa.hibernate.ddl-auto: validate`;
   - `spring.jpa.open-in-view: false`;
   - `hibernate.jdbc.time_zone: UTC`;
   - a Hikari pool of `DB_POOL_SIZE` (default 5).
4. **Schema conventions** (guideline 10.2, visible in V1–V2):
   - plural snake_case tables and `BIGINT GENERATED ALWAYS AS IDENTITY` keys;
   - `created_at` and `updated_at` as `TIMESTAMPTZ`, plus `created_by`;
   - enum columns as `VARCHAR` with `CHECK` (`ck_users_role`, `ck_vehicles_status`, `ck_vehicles_vehicle_type`,
     `ck_vehicles_fuel_type`), and `ck_vehicles_manufacture_year` (`>= 1980`);
   - named constraints (`uk_users_email`, `uk_vehicles_number_key`);
   - indexes `idx_vehicles_status` and `idx_vehicles_created_at`.
5. **Tests use an embedded real PostgreSQL (zonky), not Testcontainers.**
   - The artifacts are `io.zonky.test:embedded-database-spring-test` 2.6.0, `embedded-postgres` 2.1.0 and
     PostgreSQL binaries 17.5.0 (`pom.xml`).
   - `AuthApiTest` and `VehicleApiTest` use `@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)`,
     and Flyway runs the real migrations.
   - `TestVmsApplication` + `LocalEmbeddedPostgresConfig` (in `src/test`) give `./mvnw spring-boot:test-run`, a
     local backend with no PostgreSQL install.
   - `VehicleListPerformanceTest` runs in its own Spring context with a HikariCP pool. zonky's DataSource opens a
     new connection per transaction, which costs 85–140 ms on Windows (`docs/PERFORMANCE.md`).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| H2 for tests | A different SQL dialect, and the risk the guideline names. v2.0's partial unique indexes would not be testable. |
| `ddl-auto=update` / `create` | Hibernate would change the production schema silently, with no reviewable history. Against guideline 10.3. |
| Liquibase | Equivalent capability; Flyway is what the guideline names, and plain SQL files are easy to review. |
| Testcontainers PostgreSQL | Needs a Docker daemon. The Windows development machine has none, and CI would need Docker in the runner. zonky runs a real PostgreSQL 17.5 binary without Docker, which is the guideline's "(or a test database)". |
| MySQL | No partial unique indexes (guideline 10.3), which v2.0 needs. |

## Consequences

**Positive**
- The real constraints are exercised in tests. For example, `VehicleApiTest` proves that `uk_vehicles_number_key`
  turns a race into the same 409 ([0009](0009-vehicle-number-normalization-and-duplicate-check.md)).
- A schema that does not match the entities fails at start-up, not at the first query.
- `mvn -B verify` in CI needs only a JDK.
- v2.0 could add V3–V6 without touching V1–V2.

**Negative / accepted trade-offs**
- zonky downloads its PostgreSQL binaries as a Maven dependency.
- The embedded version (17.5) is not pinned to Render's server version, and `render.yaml` does not pin a PostgreSQL
  major version.
- Without `open-in-view`, anything a mapper needs must be loaded inside the service transaction.
- The database CHECK for the manufacture year covers only `>= 1980`. "Not after next year" depends on "today", so
  it is enforced by the DTO validator with the injected clock ([0011](0011-time-and-expiry-with-injected-clock.md)).

**Follow-ups**
- Pin the Render PostgreSQL major version and match the zonky binaries to it.

## Related

- Guideline: sections 8 (Database, Migrations), 10.1–10.3, 15, 18.3, 21.
- ADRs: [0009](0009-vehicle-number-normalization-and-duplicate-check.md), [0011](0011-time-and-expiry-with-injected-clock.md),
  [0020](0020-test-strategy-and-ci.md).
- Files: `stark-vms-backend/src/main/resources/application.yml`, `stark-vms-backend/src/main/resources/db/migration/`,
  `stark-vms-backend/pom.xml`, `stark-vms-backend/src/test/java/com/starkgroup/vms/TestVmsApplication.java`,
  `.../LocalEmbeddedPostgresConfig.java`, `.../support/ApiTestSupport.java`, `docs/ER_DIAGRAM.md`,
  `docs/PERFORMANCE.md`.
