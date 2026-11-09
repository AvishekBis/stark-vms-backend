# Assignment 1 — Stark Group VMS (v1.0, Core VMS, with v1.1 hardening)

Rebuild of the Stark Group Vehicle Management System: login, vehicle list, add / edit / delete vehicle,
duplicate vehicle-number validation, real database data. Flutter Web + Spring Boot + PostgreSQL + REST API.
**v1.1 hardening** (2026-11-08): login throttling (429 + `Retry-After`, ADR 0022) and optimistic locking on vehicle
edits (`version`, 409 `stale-version`, ADR 0023) — contract section 6.

| Folder | What it is |
|---|---|
| [stark-vms-backend/](../) | Spring Boot 3.5 / Java 21 REST API, Flyway migrations, JWT security, tests — see its [README](../README.md) |
| [stark-vms-frontend/](https://github.com/AvishekBis/stark-vms-frontend/tree/main) | Flutter Web app (Riverpod, go_router, dio) — see its [README](https://github.com/AvishekBis/stark-vms-frontend/blob/main/README.md) |
| [docs/API_CONTRACT.md](API_CONTRACT.md) | The API contract both sides are built and tested against |
| [docs/ER_DIAGRAM.md](ER_DIAGRAM.md) | Database ER diagram (v1.1: `users`, `vehicles`, from migrations V1, V2 and V2_1) |
| [docs/PERFORMANCE.md](PERFORMANCE.md) | NFR check: list endpoints with 1,000 vehicles, median / p95 via MockMvc and HTTP (all < 500 ms) |
| [docs/test-reports/](test-reports/backend-summary.md) | Test evidence of the last run: backend tests per class + JaCoCo line coverage, `flutter test --reporter expanded` output |
| [docs/adr/](adr/README.md) | Architecture Decision Records for v1.0 (contexts, data ownership, Riverpod, JWT, Problem Details, duplicate rule, NFRs, deployment) |

## Requirements covered
| ID | Feature |
|---|---|
| FR-AUTH-01 / 02 | Email + password login (JWT, BCrypt), 60-minute session, logout, expired session → login |
| FR-V01 | Vehicle list (server-side pagination, sorting, search by number) with loading / empty / error states |
| FR-V02 / V03 | Register and edit vehicle (validated on client and server, 201 + Location) |
| FR-V04 | Delete vehicle — ADMIN only, with confirmation (STAFF gets 403) |
| FR-V05 | Duplicate number rejected after normalization (`DHK-1234` = `dhk 1234` = ` DHK1234 ` → 409) |
| FR-V06 | No mock data — everything comes from the API / database |
| NFR-SEC-01 (v1.1) | Login throttling: 5 failed logins per e-mail / 20 per IP in 15 min → 429 for 15 min (ADR 0022) |
| NFR-REL-01 (v1.1) | Optimistic locking: a stale vehicle edit gets 409 and a Reload dialog instead of a lost update (ADR 0023) |
| Security review (v1.1, 2026-11-08) | Login throttling hardened: client IP from the right-most proxy-added `X-Forwarded-For` entry (a client can no longer block someone else's IP), no overshoot under parallel requests, locked e-mails never evicted, log-safe keys (ADR 0022, Amendment) |

## Architecture decisions
The architecture decisions of v1.0 are recorded as ADRs in [docs/adr/](adr/README.md). They cover:
- bounded contexts and data ownership;
- Riverpod state;
- stateless JWT and Problem Details;
- vehicle-number normalization;
- pagination;
- the injected clock;
- the NFR strategy with its measured results and known gaps;
- deployment.

The ADRs 0001–0021 were written **after** the code, and each header says so. New decisions get an ADR before they
are implemented: 0022 and 0023 (v1.1) were written on 2026-11-07 and implemented on 2026-11-08. The numbers match Assignment 2's ADRs. The gaps (0012–0014, 0017–0019) are decisions that exist
only in v2.0.

## Run it locally (Windows)
Tools used on this machine: JDK 21, Maven 3.9.9 and Flutter 3.47.7 in `C:\dev-tools`. In Git Bash:

```bash
export JAVA_HOME="$(ls -d /c/dev-tools/jdk-21*)"
export PATH="$JAVA_HOME/bin:/c/dev-tools/apache-maven-3.9.9/bin:/c/dev-tools/flutter/bin:$PATH"

# 1) Backend — embedded PostgreSQL, no install needed (http://localhost:8080, Swagger at /swagger-ui.html)
cd stark-vms-backend && ./mvnw spring-boot:test-run

# 2) Frontend — in a second terminal
cd stark-vms-frontend
flutter pub get
flutter run -d chrome --web-port 5000 --dart-define=API_BASE_URL=http://localhost:8080/api/v1
```

Demo accounts (local only, not real credentials):

| Role | Email | Password |
|---|---|---|
| ADMIN | admin@stark.local | Admin@12345 |
| STAFF | staff@stark.local | Staff@12345 |

## Tests
```bash
cd stark-vms-backend  && ./mvnw verify      # 139 tests: unit + API tests on a real embedded PostgreSQL, incl. performance
cd stark-vms-frontend && flutter analyze && flutter test   # 0 issues, 106 tests
```
Test evidence of the last run (guideline 22): [docs/test-reports/backend-summary.md](test-reports/backend-summary.md)
(tests per class, line coverage per service) and [docs/test-reports/frontend-test-report.txt](https://github.com/AvishekBis/stark-vms-frontend/blob/main/docs/test-reports/frontend-test-report.txt).
The browser end-to-end journey (`integration_test/app_journey_test.dart`, see the frontend README) last passed on
2026-11-08 in Chrome against `spring-boot:test-run`, including the vehicle edit that sends the loaded `version`.
Performance (guideline 6, < 500 ms for 1,000 vehicles): measured results in [docs/PERFORMANCE.md](PERFORMANCE.md).

## Deployment
Backend → Render (Dockerfile + `render.yaml`), frontend → Netlify (`netlify.toml`, `web/_redirects`).
Step-by-step instructions are in each project's README. Live URLs are placeholders until deployed.
