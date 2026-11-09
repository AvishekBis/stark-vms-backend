# 0021. Demo data is seeded into the real database (local profile only); the frontend has no mocks

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 built FR-V06 "real
  data only")

## Context

FR-V06 requires that the vehicle list shows real data from the database. A reviewer, a demo or a developer needs a
populated list with several pages and every status. Production must never contain demo data. The guideline's demo
accounts are non-real credentials (`docs/API_CONTRACT.md` §5). A common shortcut is mock data in the frontend, but
then the UI is never exercised against the real API.

## Decision

- **`BE/dev/DemoDataSeeder`** is a `CommandLineRunner` that inserts 26 realistic Bangladeshi vehicles through
  `VehicleRepository` into the real database. The vehicles have every status and several types, and numbers such
  as `DHAKA METRO-GA 11-2345`.
- **It runs only when `app.seed-demo-data=true`.** `application.yml` sets it to `false`, and only the `local` profile
  sets it to `true`. The local launcher (`TestVmsApplication`, `./mvnw spring-boot:test-run`) activates `local`.
- **It runs only when the `vehicles` table is empty**, so a restart does not duplicate data.
- **The demo accounts** (`admin@stark.local` / `Admin@12345`, `staff@stark.local` / `Staff@12345`) come from the
  local configuration (`src/test/resources/local-embedded.properties`) through `AdminBootstrap`, never from a
  migration.
- **The frontend has no mock repositories in the app.** Fakes exist only under `test/`.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Mock data in the Flutter app | FR-V06 forbids it in effect. The UI would not exercise the real API, paging or errors. |
| A Flyway migration with demo rows | It would also run in production, and the credentials would end up in SQL. |
| A SQL seed script run by hand | Easy to forget, and it is not part of the build. |

## Consequences

**Positive**
- A local start shows two pages of realistic vehicles.
- The E2E journey and the HTTP performance run (`docs/PERFORMANCE.md`: 26 demo vehicles plus 974 inserted) build on
  the seeded data.

**Negative / accepted trade-offs**
- `dev` depends on `vehicle` and writes its table. This is an accepted exception in
  [0003](0003-data-ownership-and-cross-context-contracts.md), limited to the local profile.
- If someone sets `SPRING_PROFILES_ACTIVE=local` on Render, demo data and the localhost CORS patterns would reach
  production. The README warns against this, but nothing in the code prevents it.

**Follow-ups**
- Refuse to seed, or at least log a loud warning, when the datasource is not local.

## Related

- Guideline: sections 5 (demo accounts), 5.1 FR-V06, 13 (first ADMIN from environment variables).
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0016](0016-deployment-render-and-netlify.md).
- Files: `BE/dev/DemoDataSeeder.java`, `stark-vms-backend/src/main/resources/application.yml` (the `local` profile
  document), `stark-vms-backend/src/test/java/com/starkgroup/vms/TestVmsApplication.java`,
  `stark-vms-backend/src/test/resources/local-embedded.properties`, `docs/API_CONTRACT.md` §5.

Path prefix: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`.
