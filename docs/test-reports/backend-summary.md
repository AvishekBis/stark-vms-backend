# Backend test report (v2.1)

Generated on 2026-11-08 from `target/surefire-reports/TEST-*.xml` and `target/site/jacoco/jacoco.csv` after `./mvnw -q -B verify` (embedded PostgreSQL, JaCoCo agent).

**Result: 366 tests, 0 failed, 0 skipped.** Line coverage 97.2 % (1910 / 1966 lines), branch coverage 83.1 %.

v2.1 (ADRs 0022–0024: login throttling, optimistic locking, ADMIN user management) added 93 tests to the 253 of v2.0; the fixes after the security review of 2026-11-08 added 19 more (new `auth.ClientIpApiTest` on a real Tomcat; more cases in `LoginAttemptServiceTest`, `AuthServiceTest`, `JwtServiceTest`, `TokenAuthenticatorTest`, `UserServiceTest`, `UserApiTest`; the MockMvc `X-Forwarded-For` case moved from `LoginThrottlingApiTest` to `ClientIpApiTest`). One more test, `MigrationUpgradeTest`, replays a v1.x database (V1, V2, V2_1) and upgrades it to the current schema (ADR 0023, note on migration numbering); it covers no new production lines, so coverage is unchanged.

## Tests per class

| Test class | Tests | Failed | Skipped | Time (s) |
|---|---:|---:|---:|---:|
| `assignment.AssignmentApiTest` | 14 | 0 | 0 | 95.2 |
| `assignment.AssignmentServiceTest` | 11 | 0 | 0 | 4.9 |
| `auth.AuthApiTest` | 11 | 0 | 0 | 10.9 |
| `auth.AuthServiceTest` | 9 | 0 | 0 | 1.0 |
| `auth.ClientIpApiTest` | 2 | 0 | 0 | 22.2 |
| `auth.JwtAuthenticationFilterTest` | 4 | 0 | 0 | 0.6 |
| `auth.JwtServiceTest` | 10 | 0 | 0 | 0.4 |
| `auth.LoginAttemptServiceTest` | 19 | 0 | 0 | 4.5 |
| `auth.LoginThrottlingApiTest` | 7 | 0 | 0 | 14.9 |
| `auth.PasswordPolicyTest` | 5 | 0 | 0 | 0.1 |
| `auth.TokenAuthenticatorTest` | 7 | 0 | 0 | 0.2 |
| `auth.UserApiTest` | 21 | 0 | 0 | 40.3 |
| `auth.UserServiceTest` | 16 | 0 | 0 | 0.2 |
| `common.exception.GlobalExceptionHandlerTest` | 14 | 0 | 0 | 0.5 |
| `common.expiry.ExpiryCalculatorTest` | 5 | 0 | 0 | 0.0 |
| `common.util.PageRequestsTest` | 6 | 0 | 0 | 0.0 |
| `common.validation.ManufactureYearValidatorTest` | 2 | 0 | 0 | 1.0 |
| `config.CorsPropertiesTest` | 2 | 0 | 0 | 0.0 |
| `dashboard.DashboardApiTest` | 3 | 0 | 0 | 9.8 |
| `dashboard.DashboardServiceTest` | 4 | 0 | 0 | 1.4 |
| `document.ContentDispositionsTest` | 5 | 0 | 0 | 0.0 |
| `document.DocumentApiTest` | 13 | 0 | 0 | 20.1 |
| `document.DocumentServiceTest` | 8 | 0 | 0 | 0.3 |
| `document.dto.ExpiryOnOrAfterIssueTest` | 2 | 0 | 0 | 0.0 |
| `document.FileValidatorTest` | 14 | 0 | 0 | 0.3 |
| `document.storage.SupabaseStorageServiceTest` | 7 | 0 | 0 | 0.9 |
| `driver.DriverApiTest` | 12 | 0 | 0 | 19.9 |
| `driver.DriverPhoneTest` | 12 | 0 | 0 | 0.3 |
| `driver.DriverServiceTest` | 13 | 0 | 0 | 0.4 |
| `driver.LicenseNumberTest` | 5 | 0 | 0 | 0.0 |
| `ListEndpointsPerformanceTest` | 13 | 0 | 0 | 26.3 |
| `maintenance.MaintenanceApiTest` | 6 | 0 | 0 | 10.0 |
| `maintenance.MaintenanceRequestValidationTest` | 2 | 0 | 0 | 0.4 |
| `maintenance.MaintenanceServiceTest` | 9 | 0 | 0 | 0.1 |
| `MigrationUpgradeTest` | 1 | 0 | 0 | 1.9 |
| `OptimisticLockingApiTest` | 9 | 0 | 0 | 13.4 |
| `vehicle.VehicleApiTest` | 30 | 0 | 0 | 39.5 |
| `vehicle.VehicleNumberTest` | 12 | 0 | 0 | 0.2 |
| `vehicle.VehicleServiceTest` | 21 | 0 | 0 | 0.4 |
| **Total** | **366** | **0** | **0** | |

## Line coverage per service

| Service | Lines covered | Line coverage |
|---|---:|---:|
| `assignment.AssignmentService` | 77 / 80 | 96.2 % |
| `auth.AuthService` | 50 / 53 | 94.3 % |
| `auth.JwtService` | 43 / 44 | 97.7 % |
| `auth.LoginAttemptService` | 82 / 82 | 100.0 % |
| `auth.TokenAuthenticator` | 12 / 12 | 100.0 % |
| `auth.UserService` | 46 / 47 | 97.9 % |
| `dashboard.DashboardService` | 40 / 40 | 100.0 % |
| `document.DocumentService` | 51 / 53 | 96.2 % |
| `document.storage.DatabaseStorageService` | 10 / 10 | 100.0 % |
| `document.storage.SupabaseStorageService` | 52 / 58 | 89.7 % |
| `driver.DriverService` | 61 / 65 | 93.8 % |
| `maintenance.MaintenanceService` | 38 / 38 | 100.0 % |
| `vehicle.VehicleService` | 57 / 57 | 100.0 % |

## Line coverage per package

| Package | Lines covered | Line coverage |
|---|---:|---:|
| `(root)` | 1 / 3 | 33.3 % |
| `assignment` | 120 / 123 | 97.6 % |
| `assignment.dto` | 2 / 2 | 100.0 % |
| `auth` | 412 / 422 | 97.6 % |
| `auth.dto` | 8 / 8 | 100.0 % |
| `common.dto` | 3 / 3 | 100.0 % |
| `common.exception` | 193 / 204 | 94.6 % |
| `common.expiry` | 19 / 19 | 100.0 % |
| `common.persistence` | 4 / 4 | 100.0 % |
| `common.util` | 29 / 29 | 100.0 % |
| `common.validation` | 15 / 15 | 100.0 % |
| `common.web` | 33 / 38 | 86.8 % |
| `config` | 66 / 66 | 100.0 % |
| `dashboard` | 44 / 44 | 100.0 % |
| `dashboard.dto` | 5 / 5 | 100.0 % |
| `dev` | 145 / 148 | 98.0 % |
| `document` | 194 / 200 | 97.0 % |
| `document.dto` | 12 / 12 | 100.0 % |
| `document.storage` | 80 / 90 | 88.9 % |
| `driver` | 188 / 192 | 97.9 % |
| `driver.dto` | 5 / 5 | 100.0 % |
| `maintenance` | 112 / 112 | 100.0 % |
| `maintenance.dto` | 6 / 6 | 100.0 % |
| `vehicle` | 209 / 211 | 99.1 % |
| `vehicle.dto` | 5 / 5 | 100.0 % |
| **Overall** | **1910 / 1966** | **97.2 %** |

`dev` holds the demo-data seeder of the `local` profile (`app.seed-demo-data=true`), which the test context does not start on its own. The full HTML report is written to `stark-vms-backend/target/site/jacoco/index.html` by every `./mvnw verify`.
