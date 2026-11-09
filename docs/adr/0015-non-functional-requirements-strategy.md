# 0015. Non-functional requirements strategy (v1.0 scope)

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the strategy was followed when v1.0 was built. It was reviewed
  attribute by attribute on 2026-11-07, when the measured performance results and the hardening fixes listed below
  were added)
- **Updated:** 2026-11-08 — v1.1 closed two of the limitations below: login throttling
  ([0022](0022-login-throttling.md), hardened after the security review of 2026-11-08) and optimistic locking on
  vehicles ([0023](0023-optimistic-locking.md)). User management stays a v1.0 gap; it is decided in Assignment 2's
  ADR 0024. Each closed item is marked "(Closed in v1.1 ...)".

## Context

Guideline section 6 lists the NFRs:
- performance: under 500 ms for 1,000 vehicles, and never full tables in the browser;
- usability;
- reliability;
- security (section 13);
- maintainability;
- testability;
- observability;
- portability.

The deployment target is fixed by the course (guideline 18):
- one free Render web service (Docker), which sleeps when idle;
- one free Render PostgreSQL;
- a Netlify static site.

v1.0 covers login and the vehicle register only. This ADR records, for each quality attribute:
- what was decided;
- the evidence;
- what is accepted as a limitation;
- what comes next.

Finding IDs (for example NFR-SEC-01) come from the review of 2026-11-07, which covered both assignments.

## Decision

The strategy is **"simple and correct on one instance, with nothing that blocks a second one"**:
- a stateless API;
- all state in PostgreSQL;
- rules enforced in the service and again by the database;
- measurements instead of guesses.

### Scalability

**Done**
- Stateless API: JWT, no HTTP session, no in-memory cache or scheduler ([0007](0007-authentication-stateless-jwt.md)).
- All state is in PostgreSQL, and nothing is written to local disk.
- The list is paged on the server with a size cap of 100 ([0010](0010-server-side-pagination-filtering-sorting.md)).
- The Hikari pool is small and configurable (`DB_POOL_SIZE`, default 5).

**Accepted limitations**
- One free Render instance and one free database are the whole capacity.
- `AdminBootstrap` does check-then-insert on every instance at start-up. Two instances that start together can race
  on `uk_users_email`, and the second one then aborts its start-up (NFR-SCAL-02).

**Follow-ups**
- Make the bootstrap safe to run concurrently (`ON CONFLICT DO NOTHING`, or catch the violation).

### Availability

**Done**
- `/actuator/health` is public and shows no details, and probes are enabled. It is Render's health check path
  (`render.yaml`).
- The container runs with `-XX:+ExitOnOutOfMemoryError` (`stark-vms-backend/Dockerfile`).
- Cold start, on the frontend:
  - each request waits up to 75 s (`AppConfig.requestTimeout`);
  - after 3 s the app shows "Connecting to server…" (`SlowRequestMonitor`, `FE/shared/widgets/connecting_banner.dart`);
  - since 2026-11-07, a fire-and-forget `GET /actuator/health` at start-up wakes the backend while the user types
    (`FE/bootstrap.dart`).
- An unreachable database returns 503 with a one-line WARN log (2026-11-07, [0008](0008-error-format-problem-details.md)).

**Accepted limitations**
- The free instance sleeps, so the first request can take close to a minute (guideline 18.4).
- One instance means a short outage on every deploy.
- There is no backup or restore procedure in the repository.

**Follow-ups**
- A paid instance with no sleep.
- A written `pg_dump` backup procedure.

### Performance

**Done**
- **Measured and met** (`docs/PERFORMANCE.md`, 2026-11-07). With 1,000 vehicles, every list request has a p95 of:
  - 31–92 ms via MockMvc;
  - 36–90 ms over real HTTP with gzip.

  The requests measured were the default list, page 49, size 100, three number searches and two sorts. The machine
  was loaded and the JaCoCo agent was on, so the numbers are pessimistic.
- `VehicleListPerformanceTest` (8 tests) asserts p95 < 500 ms in every `./mvnw verify`. It uses its own embedded
  PostgreSQL and a HikariCP pool of 5.
- `size` is capped at 100, so the browser never gets the full table.
- JSON responses of 2 KB or more are gzip-compressed (`server.compression`, 2026-11-07).
- Search is debounced by 350 ms.

**Accepted limitations**
- The Render cold start is outside the NFR, and it is the largest delay a user sees.
- `%term%` search and `count(*)` use sequential scans.

**Follow-ups**
- Re-measure on the deployed instance.
- Trigram indexes and keyset paging only if the data grows ([0010](0010-server-side-pagination-filtering-sorting.md)).

### Security

**Done** (details in [0007](0007-authentication-stateless-jwt.md))
- Credentials:
  - BCrypt hashes;
  - a timing-equalised generic 401;
  - a 256-bit `JWT_SECRET` from the environment;
  - a 60-minute token.
- Access control:
  - `@PreAuthorize("hasRole('ADMIN')")` on delete;
  - an explicit CORS list (`*` is rejected).
- Input and output:
  - parameter-bound queries with escaped `LIKE` input;
  - no stack traces in responses.
- Secrets and deployment:
  - no secrets in Git. `.gitignore` covers `.env*`, `application-local.yml`, `*.pem`, `*.key`, `*.jks`, `*.p12`,
    `*.pfx` and `*.keystore`/`key.properties` (frontend);
  - `render.yaml` uses `sync: false` and a generated `JWT_SECRET`;
  - a non-root container user.
- Frontend:
  - Netlify security headers (2026-11-07);
  - the demo-credentials hint is hidden in release builds unless `SHOW_DEMO_CREDENTIALS=true` (2026-11-07).

**Accepted limitations**
- The token is kept in `localStorage`, and there is no refresh token or revocation.
- **No rate limiting or lockout on login** (NFR-SEC-01). Each attempt costs a BCrypt check.
  *(Closed in v1.1, 2026-11-08, by [0022](0022-login-throttling.md): 5 failures per e-mail / 20 per IP in 15 minutes
  → 429 without a BCrypt check. After the security review the IP is the right-most proxy-added address, attempts are
  reserved before the password check and locks are never evicted. Remaining: the state is in memory per instance.)*
- There is no full Content-Security-Policy.
- There is no user management (guideline section 4 "manage user accounts"; see 0007). *(Still open in v1.1; closed in
  Assignment 2 v2.1 by its ADR 0024.)*

**Follow-ups**
- ~~A login rate limit and lockout.~~ Done ([0022](0022-login-throttling.md)).
- A full CSP.
- A refresh-cookie design.
- ADMIN-only user management.

### Maintainability

**Done**
- Package-by-feature with a small shared kernel ([0002](0002-bounded-contexts-and-package-by-feature.md)), and
  layering ([0005](0005-layered-architecture-and-solid-conventions.md)).
- Zero javac warnings with `-Xlint:all` (2026-11-07). `flutter analyze` reports 0 issues, and CI runs
  `dart format --set-exit-if-changed`.
- Constructor injection everywhere, including the tests.
- The documents `docs/API_CONTRACT.md`, `docs/ER_DIAGRAM.md` and these ADRs.
- The "ready for v2.0" seams (`stark-vms-frontend/README.md`):
  - a generic `PageResponse`;
  - a generic `StatusBadge`;
  - `VehicleQuery`;
  - `clockProvider`.

**Accepted limitations**
- The constraint-race → 409 mapping exists twice (0005, NFR-MAINT-01).
- Boundaries are a convention; there is no architecture test.

**Follow-ups**
- One constraint-race mechanism.
- An ArchUnit test.

### Reliability

**Done**
- Validation runs on the client (`FE/core/utils/validators.dart`) and on the server (`@Valid`). The database
  constraints are the last line of defence:
  - `uk_vehicles_number_key` and `uk_users_email`;
  - `CHECK`s for role, status, type, fuel and the year lower bound.
- Every write is one transaction, and a race on the vehicle number gives the same 409 ([0009](0009-vehicle-number-normalization-and-duplicate-check.md)).
- Time comes from the injected clock ([0011](0011-time-and-expiry-with-injected-clock.md)).

**Accepted limitations**
- **No optimistic locking** on vehicles (no `@Version`, no `ETag`/`If-Match`). When two users edit the same
  vehicle, the last PUT silently wins (NFR-REL-01).
  *(Closed in v1.1, 2026-11-08, by [0023](0023-optimistic-locking.md): `version` + 409 `stale-version`.)*

**Follow-ups**
- ~~`@Version` with `If-Match`/409 for edits.~~ Done with a `version` field and 409 ([0023](0023-optimistic-locking.md)).

### Other section 6 attributes (short)

- **Testability.**
  - Mockito unit tests run with fixed clocks, and the API tests run against a real embedded PostgreSQL. In total
    `./mvnw verify` runs 139 backend tests (v1.1 with the security-review fixes), with 93.5 % line coverage (100 % in `VehicleService`; `docs/test-reports/backend-summary.md`).
  - The frontend has 106 unit and widget tests (v1.1) with provider-override fakes (`README.md`).
  - No coverage threshold is enforced ([0020](0020-test-strategy-and-ci.md)).
- **Observability.**
  - `RequestLoggingFilter` logs one line per request (`method`, `path`, `status`, `durationMs`), with no query
    strings or bodies. `/actuator/health` is the only actuator endpoint.
  - Limitation (NFR-MAINT-03): plain key=value text with no request id, no user or IP, and no JSON.
- **Usability.**
  - Responsive breakpoints: cards below 600 px, a compact table up to 1024 px, a full table above.
  - Four states on every data screen.
  - Status badges always show text as well as colour.
- **Portability.** All configuration comes from environment variables (backend) or `--dart-define` (frontend)
  ([0016](0016-deployment-render-and-netlify.md)).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Design for horizontal scale now (cache, broker, replicas) | One free instance and one database; the measured latency is under a fifth of the budget. |
| Treat the NFRs as satisfied by design, without measuring | The guideline gives a number. `docs/PERFORMANCE.md` and the asserting test turn it into evidence that cannot silently regress. |
| Fix every finding in v1.0 | Several fixes (optimistic locking, rate limiting, refresh tokens) need contract or schema changes and their own ADRs. |

## Consequences

**Positive**
- Every NFR has evidence or a named limitation.
- The follow-ups form a backlog ordered by risk: login rate limit, optimistic locking, bootstrap race, structured
  logs.

**Negative / accepted trade-offs**
- The gaps above remain in v1.0. Assignment 2 (v2.0) still has them, too: see its ADR 0015.

**Follow-ups**
- Each follow-up that changes the API, the schema or security gets its own ADR before implementation
  ([0001](0001-record-architecture-decisions.md)).

## Related

- Guideline: sections 6, 13, 14.2, 15, 18.4, 21.
- ADRs: 0002, 0005, 0007, 0008, 0009, 0010, 0011, 0016, 0020.
- Files: `docs/PERFORMANCE.md`,
  `stark-vms-backend/src/test/java/com/starkgroup/vms/vehicle/VehicleListPerformanceTest.java`,
  `stark-vms-backend/src/main/resources/application.yml`, `stark-vms-backend/Dockerfile`,
  `stark-vms-backend/render.yaml`, `BE/common/web/RequestLoggingFilter.java`, `BE/auth/AdminBootstrap.java`,
  `FE/bootstrap.dart`, `FE/core/config/app_config.dart`, `stark-vms-frontend/netlify.toml`,
  `stark-vms-backend/README.md` (Known limitations).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
