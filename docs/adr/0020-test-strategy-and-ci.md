# 0020. Test strategy and continuous integration

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; the
  performance test was added on 2026-11-07)

## Context

Guideline section 15 asks for a test pyramid:
- many unit tests (JUnit 5 and Mockito; `flutter_test`);
- fewer API tests (`@SpringBootTest`, MockMvc, "Testcontainers PostgreSQL (or a test database)") that cover status
  codes, validation, 401/403, pagination and real SQL constraints;
- a few end-to-end journeys with Flutter `integration_test`. For v1.0 the journey is: login → add vehicle →
  duplicate blocked → edit → delete.

It also names the key test cases TC-01…TC-07 and TC-11 for v1.0, and asks for deterministic date tests. The
testability NFR aims at ≥ 70 % line coverage on services, with "meaningful tests, not vanity numbers". Section 18.2
describes the two CI workflows.

## Decision

### Backend (`stark-vms-backend/src/test/java/com/starkgroup/vms/`)

- **Unit tests:**
  - `VehicleNumberTest`, `JwtServiceTest`, `PageRequestsTest`, `ManufactureYearValidatorTest` (fixed
    Asia/Dhaka clock), `CorsPropertiesTest` and `GlobalExceptionHandlerTest`;
  - `VehicleServiceTest`, which uses Mockito.
- **API tests:** `AuthApiTest` and `VehicleApiTest` use `@SpringBootTest` + MockMvc +
  `@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)`.
  - They run against a real PostgreSQL 17.5 with the Flyway schema ([0006](0006-postgresql-everywhere-flyway-and-validate.md)).
  - They log in for real through `support/ApiTestSupport.java`.
- **Performance test:** `VehicleListPerformanceTest` (8 parameterized cases) runs in its own Spring context with
  its own embedded PostgreSQL and a HikariCP pool of 5. It seeds 1,000 vehicles and asserts p95 < 500 ms
  (`docs/PERFORMANCE.md`).
- **Coverage:** JaCoCo runs `prepare-agent` and writes the report at `verify` (`pom.xml`). The README reports
  93.5 % line coverage at v1.1 (100 % in `VehicleService`, `docs/test-reports/backend-summary.md`). **No threshold** fails the build.
- **Compiler:** `-Xlint:all` (without `processing` and `serial`), with zero warnings.
- **Count:** `./mvnw verify` runs 139 tests at v1.1 (129 before the fixes of the security review of 2026-11-08; `stark-vms-backend/README.md`, Tests).

### Frontend (`stark-vms-frontend/test/`)

- **Unit tests** (`test/unit/`) cover:
  - the JSON models;
  - the validators and `VehicleQuery`;
  - `ApiClient` and `ApiException`;
  - `AuthController` (with a `FixedClock`), `authRedirect` and `AppConfig`.
- **Widget tests** (`test/widget/`) cover the login, list, form and detail screens, the status badge, the state
  views and the missing-configuration screen.
  - Fake repositories are injected through provider overrides (`test/helpers/fakes.dart`, `pump_app.dart`).
- **End-to-end:** `integration_test/app_journey_test.dart` runs the v1.0 journey against a running backend with
  `flutter drive` and ChromeDriver. It is **not in CI**.
- **Static checks:** `flutter_lints` 6.0.0, `flutter analyze` with 0 issues, and `dart format --set-exit-if-changed`.
- **Count:** 106 tests at v1.1 (`README.md`, Tests).

### CI (GitHub Actions)

- Backend: `mvn -B verify` on Temurin 21, on every push and pull request.
- Frontend: format → analyze → test → `flutter build web --release`, on every push to any branch and on pull
  requests. The details are in [0016](0016-deployment-render-and-netlify.md).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| H2 for API tests | A different SQL dialect, so the real constraints would not be tested (0006). |
| Testcontainers | Needs Docker, which the development machine does not have (0006). |
| Only `@WebMvcTest` with mocked services | Would not prove that the `uk_vehicles_number_key` race returns the same 409. |
| A JaCoCo `check` threshold | Easy to add. Not added, because a threshold invites vanity tests. It is kept as a follow-up. |
| Run the E2E journey in CI | Needs a running backend with a database and ChromeDriver in the runner. That is more CI setup than v1.0 needs. |

## Consequences

**Positive**
- TC-01…TC-07 and TC-11 have automated tests. The mapping is in `stark-vms-backend/README.md`, Tests.
- The database safety net is proven against real PostgreSQL in every build.
- The frontend tests run in seconds without a backend.

**Negative / accepted trade-offs**
- zonky opens a new connection per transaction (85–140 ms on Windows), which makes the API tests slower.
- The E2E journey runs only when someone runs it by hand.
- Coverage can drop without failing the build.

**Follow-ups**
- A JaCoCo `check` rule on the service packages.
- An optional CI job for the E2E journey.
- Architecture tests for 0002, 0003 and 0005.

## Related

- Guideline: sections 6 (Testability), 15, 15.1, 16.3, 18.2.
- ADRs: [0006](0006-postgresql-everywhere-flyway-and-validate.md), [0011](0011-time-and-expiry-with-injected-clock.md),
  [0015](0015-non-functional-requirements-strategy.md), [0016](0016-deployment-render-and-netlify.md).
- Files: `stark-vms-backend/pom.xml`, `stark-vms-backend/src/test/`, `stark-vms-backend/.github/workflows/ci.yml`,
  `stark-vms-frontend/test/`, `stark-vms-frontend/integration_test/app_journey_test.dart`,
  `stark-vms-frontend/analysis_options.yaml`, `stark-vms-frontend/.github/workflows/ci.yml`, `docs/PERFORMANCE.md`.
