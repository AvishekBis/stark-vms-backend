# Backend test report (v1.1)

Generated on 2026-11-08 from `target/surefire-reports/TEST-*.xml` and `target/site/jacoco/jacoco.csv` after `./mvnw -q -B verify` (embedded PostgreSQL, JaCoCo agent).

**Result: 139 tests, 0 failed, 0 skipped.** Line coverage 93.5 % (710 / 759 lines), branch coverage 79.6 %.

v1.1 (ADR 0022 login throttling, ADR 0023 optimistic locking on vehicles) added `auth.AuthServiceTest`, `auth.LoginAttemptServiceTest`, `auth.LoginThrottlingApiTest` and `vehicle.VehicleOptimisticLockingApiTest`, and extended `GlobalExceptionHandlerTest`, `VehicleServiceTest` and `VehicleApiTest`. The fixes after the security review of 2026-11-08 added `auth.ClientIpApiTest` (real Tomcat on a random port) and more cases in `LoginAttemptServiceTest` and `AuthServiceTest`; the MockMvc `X-Forwarded-For` case moved from `LoginThrottlingApiTest` to `ClientIpApiTest`.

## Tests per class

| Test class | Tests | Failed | Skipped | Time (s) |
|---|---:|---:|---:|---:|
| `auth.AuthApiTest` | 11 | 0 | 0 | 84.1 |
| `auth.AuthServiceTest` | 5 | 0 | 0 | 6.9 |
| `auth.ClientIpApiTest` | 2 | 0 | 0 | 29.3 |
| `auth.JwtServiceTest` | 7 | 0 | 0 | 0.1 |
| `auth.LoginAttemptServiceTest` | 19 | 0 | 0 | 4.5 |
| `auth.LoginThrottlingApiTest` | 7 | 0 | 0 | 15.0 |
| `common.exception.GlobalExceptionHandlerTest` | 9 | 0 | 0 | 0.1 |
| `common.util.PageRequestsTest` | 6 | 0 | 0 | 0.1 |
| `common.validation.ManufactureYearValidatorTest` | 2 | 0 | 0 | 0.7 |
| `config.CorsPropertiesTest` | 2 | 0 | 0 | 0.0 |
| `vehicle.VehicleApiTest` | 25 | 0 | 0 | 20.8 |
| `vehicle.VehicleListPerformanceTest` | 8 | 0 | 0 | 14.4 |
| `vehicle.VehicleNumberTest` | 12 | 0 | 0 | 0.2 |
| `vehicle.VehicleOptimisticLockingApiTest` | 7 | 0 | 0 | 6.2 |
| `vehicle.VehicleServiceTest` | 17 | 0 | 0 | 0.7 |
| **Total** | **139** | **0** | **0** | |

## Line coverage per service

| Service | Lines covered | Line coverage |
|---|---:|---:|
| `auth.AuthService` | 33 / 33 | 100.0 % |
| `auth.JwtService` | 38 / 39 | 97.4 % |
| `auth.LoginAttemptService` | 82 / 82 | 100.0 % |
| `vehicle.VehicleService` | 35 / 35 | 100.0 % |

## Line coverage per package

| Package | Lines covered | Line coverage |
|---|---:|---:|
| `(root)` | 1 / 3 | 33.3 % |
| `auth` | 253 / 259 | 97.7 % |
| `auth.dto` | 3 / 3 | 100.0 % |
| `common.dto` | 3 / 3 | 100.0 % |
| `common.exception` | 146 / 158 | 92.4 % |
| `common.persistence` | 4 / 4 | 100.0 % |
| `common.util` | 25 / 25 | 100.0 % |
| `common.validation` | 15 / 15 | 100.0 % |
| `common.web` | 33 / 38 | 86.8 % |
| `config` | 63 / 63 | 100.0 % |
| `dev` | 0 / 21 | 0.0 % |
| `vehicle` | 161 / 164 | 98.2 % |
| `vehicle.dto` | 3 / 3 | 100.0 % |
| **Overall** | **710 / 759** | **93.5 %** |

`dev` holds the demo-data seeder of the `local` profile (`app.seed-demo-data=true`), which the test context does not start on its own. The full HTML report is written to `stark-vms-backend/target/site/jacoco/index.html` by every `./mvnw verify`.
