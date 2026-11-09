# Assignment 2 — Stark Group VMS (v2.1, Advanced Fleet Management)

Built **on top of Assignment 1** (same code base, all v1 features and tests kept): documents with expiry
tracking, maintenance history, vehicle status, server-side search / filter / sort / pagination, drivers,
driver–vehicle assignment and a dashboard. Flutter Web + Spring Boot + PostgreSQL + REST API.
v2.1 adds the hardening after the v2.0 review (login throttling, optimistic locking, ADMIN user management) and the
fixes from the security review of 2026-11-08 (see below).

| Folder | What it is |
|---|---|
| [stark-vms-backend/](../) | Spring Boot 3.5 / Java 21 REST API, Flyway migrations V1–V8 (plus V2_1), JWT security, tests — see its [README](../README.md) |
| [stark-vms-frontend/](https://github.com/AvishekBis/stark-vms-frontend/tree/main) | Flutter Web app (Riverpod, go_router, dio, file_picker) — see its [README](https://github.com/AvishekBis/stark-vms-frontend/blob/main/README.md) |
| [docs/API_CONTRACT.md](API_CONTRACT.md) | API contract; Section 6 is the v2.0 part, Section 7 the v2.1 hardening |
| [docs/ER_DIAGRAM.md](ER_DIAGRAM.md) | Database ER diagram (v2.1: all tables of migrations V1–V8 and V2_1) |
| [docs/PERFORMANCE.md](PERFORMANCE.md) | NFR check: vehicle / driver / document lists and dashboard with 1,000 vehicles, median / p95 via MockMvc and HTTP (all < 500 ms) |
| [docs/test-reports/](test-reports/backend-summary.md) | Test evidence of the last run: backend tests per class + JaCoCo line coverage, `flutter test --reporter expanded` output |
| [docs/adr/](adr/README.md) | Architecture Decision Records 0001–0024 (bounded contexts, data ownership, auth, storage, NFRs, deviations; 0022–0024 = v2.1 hardening) |

## The ten Assignment 2 features
| ID | Feature | Where in the app |
|---|---|---|
| FR-D01 | Upload Registration / Tax Token / Insurance / Fitness / Route Permit / Other (PDF, JPG, PNG ≤ 5 MB), view and download | Vehicle → Documents tab |
| FR-D02 | Expiry status VALID / EXPIRING_SOON (≤ 30 days) / EXPIRED, Asia/Dhaka, colour + text badge | Documents tab, Expiring docs page, Dashboard |
| FR-M01 | Maintenance records: date, type, cost (BDT), vendor, odometer, notes; edit / delete | Vehicle → Maintenance tab |
| FR-M02 | Per-vehicle history, newest first, paginated, total cost | Vehicle → Maintenance tab |
| FR-V07 | Status ACTIVE / INACTIVE / MAINTENANCE via a dedicated ADMIN action | Vehicle → Overview → Change status |
| FR-V08 | Search by number, brand, model or current driver name (on the server) | Vehicle list search box |
| FR-V09 | Server-side filter (status, type), sort, pagination (default 20, max 100) | Vehicle list |
| FR-DR01 | Driver CRUD, unique phone and licence number, licence expiry | Drivers |
| FR-DR02 | Assign / change / unassign driver; one current driver per vehicle and vehicle per driver; history kept | Vehicle → Overview, Driver history tab |
| FR-DB01 | Dashboard: vehicle counts by status, documents expired / expiring, maintenance this month, drivers | Dashboard |

Also: FR-X01 audit fields (createdAt / updatedAt / createdBy), FR-X02 driver licence expiry on the dashboard,
BR-10 — a vehicle or driver with history cannot be deleted (409, the app offers "Set INACTIVE instead").

## Hardening after the v2.0 review (v2.1)
| Finding / ID | Feature | Where in the app | ADR |
|---|---|---|---|
| NFR-SEC-01 | Login throttling: 5 failed logins per e-mail / 20 per IP in 15 min → 429 + `Retry-After` for 15 min; the login screen shows the server message | Login | [0022](adr/0022-login-throttling.md) |
| NFR-REL-01 | Optimistic locking: `version` on vehicles, drivers, maintenance records and users; a stale edit gets 409 and a "Changed by someone else — Reload" dialog instead of a lost update | Every edit form | [0023](adr/0023-optimistic-locking.md) |
| Guideline 4 (ADR 0017 gap) | ADMIN user management: list / search / filter / sort users, add, edit role and enabled (disable with confirmation), reset password; guards against self-modification and removing the last ADMIN | Users (ADMIN only) | [0024](adr/0024-admin-user-management.md) |
| Guideline 13 | Change own password (every role, the app logs in again automatically); disabling, demoting or resetting a user takes effect on their next request | User menu → Change password | [0024](adr/0024-admin-user-management.md) |
| Security review 2026-11-08 | Client IP taken from the right-most proxy-added `X-Forwarded-For` entry (a client can no longer block someone else's IP), no throttle overshoot under parallel requests, locked e-mails never evicted, log-safe keys, millisecond token cut-off, tokens revoked on disable (migration V8), wrong current passwords throttled | Backend | Amendments in [0022](adr/0022-login-throttling.md), [0024](adr/0024-admin-user-management.md) |

![Users list (ADMIN): search, role and status filters, sort, enabled badge, edit and reset-password actions](https://github.com/AvishekBis/stark-vms-frontend/blob/main/docs/screenshots/users_list.png)

## Architecture decisions
The architecture decisions are recorded as ADRs in [docs/adr/](adr/README.md):
- bounded contexts and data ownership;
- Riverpod state;
- JWT auth and Problem Details;
- document storage, the assignment model and BR-10;
- the NFR strategy with its measured results and known gaps;
- user-management scope and accepted deviations from the guideline;
- v2.1: login throttling (0022), optimistic locking (0023) and ADMIN user management (0024), each with an
  amendment after the security review of 2026-11-08.

They were written **after** the code and say so in each header. New decisions get an ADR before they are
implemented. ADRs 0012–0014 and 0017–0019 are new in v2.0; ADRs 0022–0024 were written before the v2.1 code; the
others line up with Assignment 1's ADRs.

## Run it locally (Windows)
Tools used on this machine: JDK 21, Maven 3.9.9 and Flutter 3.47.7 in `C:\dev-tools`. In Git Bash:

```bash
export JAVA_HOME="$(ls -d /c/dev-tools/jdk-21*)"
export PATH="$JAVA_HOME/bin:/c/dev-tools/apache-maven-3.9.9/bin:/c/dev-tools/flutter/bin:$PATH"

# 1) Backend — embedded PostgreSQL, demo drivers / documents / maintenance seeded
cd stark-vms-backend && ./mvnw spring-boot:test-run        # http://localhost:8080, Swagger at /swagger-ui.html

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
cd stark-vms-backend  && ./mvnw verify                      # 366 tests incl. all v1 / v2.0 tests and performance, real embedded PostgreSQL
cd stark-vms-frontend && flutter analyze && flutter test    # 0 issues, 315 tests incl. all v1 and v2.0 tests (v2.1)
```
The browser end-to-end journey (`integration_test/app_journey_test.dart`) is described in the frontend README. Last
run 2026-11-08 in Chrome against `spring-boot:test-run`: passed (v1 vehicle journey with the `version` round-trip on
edit, v2.0 document / dashboard journey, v2.1 Users: add a STAFF user, find it, disable it with confirmation).
Test evidence of the last run (guideline 22): [docs/test-reports/backend-summary.md](test-reports/backend-summary.md)
(tests per class, line coverage per service) and [docs/test-reports/frontend-test-report.txt](https://github.com/AvishekBis/stark-vms-frontend/blob/main/docs/test-reports/frontend-test-report.txt).
Performance (guideline 6, < 500 ms for 1,000 vehicles): measured results in [docs/PERFORMANCE.md](PERFORMANCE.md).

## Deployment
Backend → Render (Dockerfile + `render.yaml`; documents are stored in PostgreSQL by default so they survive
redeploys, Supabase Storage optional), frontend → Netlify (`netlify.toml`, `web/_redirects`).
Step-by-step instructions are in each project's README. Live URLs are placeholders until deployed.
