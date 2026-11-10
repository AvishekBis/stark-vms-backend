# 0020. Test strategy and continuous integration

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; v2.0 kept
  every v1.0 test as a regression test and added its own, and the performance test was added on 2026-11-07)

## Context

Guideline section 15 asks for a test pyramid:
- many unit tests (JUnit 5, Mockito; `flutter_test`);
- fewer API tests (`@SpringBootTest`, MockMvc, "Testcontainers PostgreSQL (or a test database)"), covering
  status codes, validation, 401/403, pagination and real SQL constraints;
- a few end-to-end journeys (Flutter `integration_test`);
- the key test cases TC-01…TC-12, and deterministic date tests with `Clock.fixed(...)`.

The testability NFR aims at ≥ 70 % line coverage on services, "meaningful tests, not vanity numbers". Section 18.2
describes the two CI workflows. Tests are "part of done".

## Decision

### Backend (`stark-vms-backend/src/test/java/com/starkgroup/vms/`, mirrors the main packages)

- **Unit tests:**
  - plain JUnit for value rules: `VehicleNumberTest`, `DriverPhoneTest`, `LicenseNumberTest`,
    `ExpiryCalculatorTest`, `FileValidatorTest`, `ContentDispositionsTest`, `PageRequestsTest`,
    `ManufactureYearValidatorTest`, `JwtServiceTest`, `CorsPropertiesTest`;
  - Mockito for services: `VehicleServiceTest`, `DriverServiceTest`, `AssignmentServiceTest`,
    `DocumentServiceTest`, `MaintenanceServiceTest`, `DashboardServiceTest`;
  - `GlobalExceptionHandlerTest`;
  - Bean Validation tests: `ExpiryOnOrAfterIssueTest`, `MaintenanceRequestValidationTest` (standalone MockMvc);
  - `SupabaseStorageServiceTest` with `MockRestServiceServer`.
  - Every date rule uses `Clock.fixed(...)` in Asia/Dhaka ([0011](0011-time-and-expiry-with-injected-clock.md)).
- **API tests:** one `*ApiTest` per context (`Auth`, `Vehicle`, `Driver`, `Assignment`, `Document`, `Maintenance`,
  `Dashboard`).
  - They use `@SpringBootTest` + MockMvc + `@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)`:
    a real PostgreSQL 17.5 with the Flyway schema ([0006](0006-postgresql-everywhere-flyway-and-validate.md)).
  - They log in for real through `support/ApiTestSupport.java` and `support/FleetApi.java`, so the JWT filter
    and `@PreAuthorize` are exercised.
  - Dates are relative to Dhaka "today", so the result does not depend on the day the tests run.
- **Performance test:** `ListEndpointsPerformanceTest` (13 tests) has its own Spring context, its own embedded
  PostgreSQL and a HikariCP pool of 5. It seeds 1,000 vehicles and the related rows and asserts p95 < 500 ms per
  list request (`docs/PERFORMANCE.md`).
- **Coverage:** JaCoCo `prepare-agent` + `report` at `verify` (`pom.xml`). The README reports about 97 % line
  coverage overall. **No coverage threshold** fails the build: the number is reported, not enforced.
- **Compiler:** `-Xlint:all` (minus `processing` and `serial`) with `showWarnings`, zero warnings, no `-Werror`.
- **Count:** `./mvnw verify` runs 253 tests at v2.0; 365 at v2.1 including the fixes of the security review of
  2026-11-08, and 366 with the v1 → v2 `MigrationUpgradeTest` (`stark-vms-backend/README.md`, Tests; `docs/test-reports/backend-summary.md`). Since then one API test
  class (`auth.ClientIpApiTest`) runs on a real Tomcat with a random port, because the `X-Forwarded-For` handling
  of Tomcat's `RemoteIpValve` cannot be seen through MockMvc.

### Frontend (`stark-vms-frontend/test/`)

- **Unit tests** (`test/unit/`): JSON parsing of every model, validators, the query classes, `ApiClient`,
  `ApiException`, `FormErrors`, `AuthController` and `authRedirect`, the expiry rules, the business date and the
  model rules.
- **Widget tests** (`test/widget/`):
  - each screen with fake repositories and controllers through provider overrides
    (`test/helpers/fakes.dart`, `fakes_v2.dart`, `pump_app.dart`); no network;
  - they cover the four states, role-dependent buttons, server field errors under the right field, and the
    responsive layouts at 360, 800 and 1400 px.
- **End-to-end:** `integration_test/app_journey_test.dart`, with `test_driver/integration_test.dart`:
  - the v1.0 journey (login → add → duplicate blocked → search → edit → delete);
  - the v2.0 journey (upload a document → "Expires today" badge → dashboard count and list → BR-10 delete
    refused → cleanup);
  - the v2.1 journey (the vehicle edit sends the loaded `version`; ADMIN adds a STAFF user in Users, finds it and
    disables it with confirmation); last run 2026-11-08, passed;
  - it runs with `flutter drive` against a running backend and ChromeDriver. It is **not in CI** (see
    [0018](0018-accepted-deviations-from-the-guideline.md), D10).
- **Static checks:** `flutter_lints` 6.0.0 (`analysis_options.yaml`), `flutter analyze` with 0 issues,
  `dart format --set-exit-if-changed`.
- **Count:** 243 tests at v2.0; 315 at v2.1; 318 after the navy-and-gold restyle of
  [0025](0025-visual-identity-navy-and-gold.md), which changed no test and added 3 for the login demo hint
  (`test/unit/app_config_test.dart`) (`stark-vms-frontend/README.md`, Tests).
- **Saved evidence:** `docs/test-reports/backend-summary.md` and `docs/test-reports/frontend-test-report.txt`
  (guideline 22).

### CI (GitHub Actions, one workflow per repository)

- Backend: on every push to any branch and on pull requests, run `mvn -B verify` on Temurin 21, then upload the
  surefire and JaCoCo reports.
- Frontend: on every push to any branch and on pull requests, run format check → `flutter analyze` →
  `flutter test` → `flutter build web --release`, then upload `build/web`.
- Both workflows are byte-identical between Assignment 1 and Assignment 2.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| H2 for API tests | Different SQL; the partial unique indexes and CHECKs would not be tested (0006). |
| Testcontainers | Needs Docker, which the development machine does not have (0006, 0018 D4). |
| Mock the database in API tests (`@WebMvcTest` with mocked services only) | Would not test the real constraints, which are the BR-01/BR-06/BR-10 safety nets. Controller mapping is still covered by the API tests. |
| An enforced coverage threshold (JaCoCo `check`) | Easy to add. Not added, because a threshold invites vanity tests; the guideline asks for meaningful tests. Kept as a follow-up at the current level minus a margin. |
| Run the E2E journey in CI | Needs a database, a running backend and ChromeDriver in the runner. Possible (embedded PostgreSQL plus `spring-boot:test-run`), but more CI setup than the course needs. |
| Golden (screenshot) tests | Brittle across Flutter versions and fonts; the widget tests assert behaviour and layout overflow instead. |

## Consequences

**Positive**
- Every TC-01…TC-12 has at least one automated test (mapping in `stark-vms-backend/README.md`, Tests).
- The database safety nets are proven against real PostgreSQL in every build.
- Frontend tests run in seconds, with no backend.

**Negative / accepted trade-offs**
- zonky opens a new PostgreSQL connection per transaction. On Windows that costs 85–140 ms each, which makes the
  API tests slower than they need to be (it is why the performance test has its own pooled DataSource).
- The E2E journey depends on a manual run; a regression there is only seen when someone runs it.
- Coverage can drop without failing the build.
- Change-counter wiring on the frontend (which mutation refreshes which provider) is tested only for the cases
  that have a test; nothing checks it in general ([0004](0004-frontend-state-management-with-riverpod.md)).

**Follow-ups**
- A JaCoCo `check` rule on the service packages.
- An optional CI job for the E2E journey with the embedded backend.
- Architecture tests (ArchUnit) for the rules in 0002, 0003 and 0005.
- Branch protection with both workflows required ([0016](0016-deployment-render-and-netlify.md)).

## Related

- Guideline: sections 6 (Testability), 15, 15.1 (TC-01…TC-12), 16.3, 18.2.
- ADRs: [0006](0006-postgresql-everywhere-flyway-and-validate.md), [0011](0011-time-and-expiry-with-injected-clock.md),
  [0015](0015-non-functional-requirements-strategy.md), [0016](0016-deployment-render-and-netlify.md),
  [0018](0018-accepted-deviations-from-the-guideline.md).
- Files: `stark-vms-backend/pom.xml`, `stark-vms-backend/src/test/`, `stark-vms-backend/.github/workflows/ci.yml`,
  `stark-vms-frontend/test/`, `stark-vms-frontend/integration_test/app_journey_test.dart`,
  `stark-vms-frontend/analysis_options.yaml`, `stark-vms-frontend/.github/workflows/ci.yml`, `docs/PERFORMANCE.md`.
