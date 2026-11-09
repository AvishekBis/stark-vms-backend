# 0021. Demo data is seeded into the real database (local profile only); the frontend has no mocks

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 seeded 26 demo
  vehicles; v2.0 extended the seeder with drivers, assignments, documents and maintenance, contract §6.10)

## Context

A reviewer, a demo or a developer needs a populated system: lists with several pages, every vehicle status,
documents in all three expiry states, maintenance in the current month, and drivers with and without vehicles.
Because the dashboard and the expiry badges depend on "today", fixed calendar dates would go stale. Production
must never contain demo data. The guideline (section 5) demo accounts are non-real credentials. A common
shortcut is mock data in the frontend, but then the UI is never tested against the real API.

## Decision

- **`BE/dev/DemoDataSeeder`** (`CommandLineRunner`, `@Order(10)`, so it runs after `AdminBootstrap`) writes demo
  data through the normal JPA repositories, and `StorageService` for files, into the real database:
  - 26 vehicles;
  - 12 drivers;
  - 9 current and 4 closed assignments;
  - 20 documents spread over EXPIRED, EXPIRING_SOON and VALID, with small generated PDF/PNG files from
    `BE/dev/DemoFiles`;
  - 30 maintenance records over the last six months, 6 of them in the current month.
- **Only when `app.seed-demo-data=true`** (`@ConditionalOnProperty`). `application.yml` sets it to `false`, and
  only the `local` profile sets it to `true`. The test launcher (`TestVmsApplication`, `spring-boot:test-run`)
  uses the local profile.
- **Dates are relative to the injected `Clock`** (Asia/Dhaka), so "expiring in 10 days" stays true on every day.
- **Idempotent per part:** each part runs only when its tables are empty, so a restart does not duplicate data.
- **Names, phone and licence numbers are fake.** The demo accounts (`admin@stark.local` / `Admin@12345`,
  `staff@stark.local` / `Staff@12345`) come from the local configuration through `AdminBootstrap`, never from a
  migration (`docs/API_CONTRACT.md` §5).
- **The frontend has no mock repositories in the app.** Fakes exist only under `test/`.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Mock data in the Flutter app | The UI would not exercise the real API, paging, errors or role checks. |
| A Flyway migration (or `R__` repeatable migration) with demo rows | It would run in production too, unless it is profile-filtered; fixed dates go stale; credentials would end up in SQL. |
| A SQL seed script run by hand | Easy to forget; the dates are fixed; no file bytes for documents. |
| Seed through the REST API from a script | Needs a running backend and a token; slower; duplicates the request logic. Using the repositories keeps the seeder in the same build and in the tests. |

## Consequences

**Positive**
- A local start shows a realistic fleet with every badge and status at once.
- `DashboardApiTest` checks that the seeded data gives exactly the designed numbers and equals independent SQL
  counts, and that the seeder is idempotent. So the seeder is tested, and it tests the dashboard.
- The E2E journey and the screenshots run against the seeded data.

**Negative / accepted trade-offs**
- `dev` depends on every context and writes their tables. This is an accepted exception in
  [0003](0003-data-ownership-and-cross-context-contracts.md), limited to the local profile.
- If someone sets `SPRING_PROFILES_ACTIVE=local` (or `APP_SEED_DEMO_DATA=true`) on Render, demo data and the
  localhost CORS patterns would reach production. The READMEs warn against it, but nothing prevents it.
- The seeder must be updated when a context's rules change (for example a new required column).

**Follow-ups**
- Refuse to seed when the datasource is not local, or log a loud warning outside the local profile.

## Related

- Guideline: sections 5 (demo accounts), 13 (first ADMIN from environment variables), 15.1 TC-12.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0011](0011-time-and-expiry-with-injected-clock.md),
  [0016](0016-deployment-render-and-netlify.md), [0019](0019-dashboard-as-a-read-model.md).
- Files: `BE/dev/DemoDataSeeder.java`, `BE/dev/DemoFiles.java`, `stark-vms-backend/src/main/resources/application.yml`
  (second document, `local` profile), `stark-vms-backend/src/test/java/com/starkgroup/vms/TestVmsApplication.java`,
  `docs/API_CONTRACT.md` §5, §6.10.

Path prefix: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`.
