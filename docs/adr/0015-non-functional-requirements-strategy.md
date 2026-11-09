# 0015. Non-functional requirements strategy

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the strategy was followed when v1.0 and v2.0 were built; it
  was reviewed attribute by attribute on 2026-11-07, when the measured performance results and the hardening
  fixes listed below were added)
- **Updated:** 2026-11-08 — v2.1 closed three of the limitations below: login throttling
  ([0022](0022-login-throttling.md)), optimistic locking ([0023](0023-optimistic-locking.md)) and user management
  with token revocation ([0024](0024-admin-user-management.md)), all hardened after the security review of
  2026-11-08. The original text is kept; each closed item is marked "(closed in v2.1)".

## Context

Guideline section 6 lists the NFRs: performance (< 500 ms for 1,000 vehicles, never full tables in the browser),
usability, reliability, security (section 13), maintainability, testability, observability and portability.
The deployment target is fixed by the course: one free Render web service (Docker, it sleeps when idle), one free
Render PostgreSQL (1 GB) and a Netlify static site (guideline 18). One developer builds everything. This ADR
records, per quality attribute, what was decided, the evidence, what is accepted as a limitation and what comes
next. Where a finding ID is given (e.g. NFR-SEC-01), it comes from the review of 2026-11-07.

## Decision

The strategy is **"simple and correct on one instance, with nothing that blocks a second one"**: stateless API,
all state in PostgreSQL, rules enforced in the service and again by the database, and measurements instead of
guesses. Each attribute below says what that means.

### Scalability

**Done**
- Stateless API: JWT, no HTTP session, no in-memory cache or scheduler ([0007](0007-authentication-stateless-jwt.md)).
  Any number of instances could serve requests behind a load balancer.
- All state lives in PostgreSQL (including document bytes by default). There is no local disk use, so instances
  are interchangeable ([0013](0013-document-file-storage-behind-storageservice.md)).
- Every list is paged on the server with a size cap of 100, and the dashboard uses COUNT/SUM queries
  ([0010](0010-server-side-pagination-filtering-sorting.md), [0019](0019-dashboard-as-a-read-model.md)).
- The Hikari pool is small and configurable (`DB_POOL_SIZE`, default 5) because free databases allow few
  connections.
- Since 2026-11-07, `StoredFile` no longer copies the byte array in the constructor or the getter, so a download
  holds one copy of the file in memory, not three.

**Accepted limitations**
- One free Render instance (0.1 CPU) and one free database are the whole capacity; nothing auto-scales.
- `AdminBootstrap` does check-then-insert at start-up on every instance. Two instances that start at the same time
  can both pass `existsByEmail`, and the second then aborts on `uk_users_email` (NFR-SCAL-02).
- With the default `database` storage, files share the 1 GB database (about 200 files at 5 MB).

**Follow-ups**
- Make `AdminBootstrap` safe to run concurrently (`ON CONFLICT DO NOTHING`, or catch the unique violation).
- Use `APP_STORAGE_PROVIDER=supabase` for real use.
- Trigram indexes and keyset paging only if the data grows by orders of magnitude (0010).

### Availability

**Done**
- Health check `/actuator/health` (public, no details, probes enabled) is Render's health check path
  (`render.yaml`).
- The container runs with `-XX:+ExitOnOutOfMemoryError`, so an out-of-memory JVM is restarted by Render instead of
  limping on (`stark-vms-backend/Dockerfile`).
- Cold start of the sleeping free instance:
  - the frontend waits up to 75 s per request (`AppConfig.requestTimeout`);
  - it shows "Connecting to server…" after 3 s (`SlowRequestMonitor`, `connecting_banner.dart`);
  - since 2026-11-07 it sends a fire-and-forget `GET /actuator/health` at start-up, so the backend starts waking
    while the user types (`FE/bootstrap.dart`, `_wakeUpBackend`).
- Outgoing HTTP to Supabase has a 5 s connect and a 30 s read timeout (`spring.http.client.*`, 2026-11-07), so a
  hanging storage cannot hold a request thread and a DB connection forever.
- An unreachable database or storage returns 503 with a one-line WARN log, not a 500 with a stack trace
  ([0008](0008-error-format-problem-details.md)).

**Accepted limitations**
- Free Render services sleep after inactivity; the first request can take close to a minute (guideline 18.4).
- One instance means every deploy or crash is a short outage. There is no uptime target.
- Backups are whatever the free Render database plan offers; there is no backup or restore procedure in the
  repository.

**Follow-ups**
- A paid instance (no sleep) and a second instance for zero-downtime deploys, after NFR-SCAL-02 is fixed.
- A written backup and restore procedure (`pg_dump` of the Render database).

### Performance

**Done**
- NFR: list endpoints < 500 ms for 1,000 vehicles. **Measured and met** (`docs/PERFORMANCE.md`, 2026-11-07):
  - dataset: 1,000 vehicles, 300 drivers, 250 current assignments, about 3,000 documents and about 5,000
    maintenance records;
  - every list request and the dashboard: p95 between 30 and 121 ms through MockMvc and between 39 and 114 ms over
    real HTTP (gzip on). The slowest p95 is the dashboard at 121 ms;
  - every SQL statement takes at most about 5 ms (`EXPLAIN ANALYZE`); the rest is framework work;
  - the numbers were taken on a loaded laptop with the JaCoCo agent on, so they are pessimistic;
  - re-checked on 2026-11-08 for v2.1, where the JWT filter loads the user on every request: MockMvc p95 between
    25.5 and 90.7 ms, no measurable change (`docs/PERFORMANCE.md`, "Re-check after v2.1").
- `ListEndpointsPerformanceTest` (13 tests) asserts `p95 < 500 ms` in every `./mvnw verify`, with its own embedded
  PostgreSQL and a HikariCP pool of 5 like production.
- `?size=1000` returns 100 rows: the browser never gets a full table.
- No N+1: the current drivers or vehicles of a page come from one `IN (...)` query; `@EntityGraph` where mappers
  need associations.
- JSON responses ≥ 2 KB are gzip-compressed (`server.compression`, 2026-11-07).
- The frontend debounces search (350 ms).

**Accepted limitations**
- Render cold start is outside the NFR ("excluding Render cold start") and is the dominant user-visible delay.
- `%term%` search and `count(*)` use sequential scans. They take 2.5 ms at 1,000 rows and will not stay that cheap
  at 100,000.
- The measurement is local; there is no load test against the deployed free instance.

**Follow-ups**
- Re-measure on the deployed instance before a real rollout.
- Composite `(created_at DESC, id DESC)` index, `pg_trgm`, keyset paging, only when the data grows (0010).

### Security

**Done** (details in [0007](0007-authentication-stateless-jwt.md), [0013](0013-document-file-storage-behind-storageservice.md))
- BCrypt hashes, and a timing-equalised login that gives the same 401 for wrong password, unknown e-mail and
  disabled user.
- HS256 JWT with a secret of at least 256 bits from `JWT_SECRET` (start-up fails otherwise) and a 60-minute expiry.
- `@PreAuthorize("hasRole('ADMIN')")` on every delete and on the vehicle status change; STAFF gets 403.
- CORS is an explicit list (`*` is rejected at start-up).
- Parameter-bound queries only, with LIKE input escaped.
- Uploads are checked by extension, content type and magic bytes, stored under a UUID key and served with
  `nosniff` and `Content-Disposition`.
- No stack traces or exception messages in responses (`server.error.*`, Problem Details).
- No secrets in Git: `application.yml` has only placeholders, and `.gitignore` covers `.env*`,
  `application-local.yml`, `*.pem`, `*.key`, `*.jks`, `*.p12`, `*.pfx` (backend) and `*.keystore`,
  `key.properties` (frontend). `render.yaml` marks secrets `sync: false` and generates `JWT_SECRET`.
- Netlify headers `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy` (2026-11-07).
- A release build hides the demo-credentials hint unless `SHOW_DEMO_CREDENTIALS=true` (2026-11-07).
- The container runs as a non-root user (uid 10001).

**Accepted limitations**
- The token is in `localStorage` (XSS exposure), and there is no refresh token or revocation (0007). *(Revocation
  closed in v2.1 by [0024](0024-admin-user-management.md): disable, demotion, password reset and own password change
  take effect on the next request; the `localStorage` exposure and the missing refresh token remain.)*
- **No rate limiting or lockout on `POST /auth/login`** (NFR-SEC-01). Online guessing is unlimited, and because
  every attempt runs BCrypt, a burst of requests can saturate the 0.1-CPU instance. *(Closed in v2.1 by
  [0022](0022-login-throttling.md): 5 failures per e-mail / 20 per IP in 15 minutes → 429 without a BCrypt check;
  after the security review the IP is the right-most proxy-added address, attempts are reserved before the password
  check, locks are never evicted, and wrong current passwords on `PUT /auth/me/password` count too. Remaining: the
  state is in memory per instance.)*
- There is no full Content-Security-Policy (`script-src`/`connect-src`), and no virus scanning of uploads.
- There is no user management, so a password can only be changed in the database ([0017](0017-user-account-management-scope.md)).
  *(Closed in v2.1 by [0024](0024-admin-user-management.md), which supersedes 0017.)*

**Follow-ups**
- ~~Login rate limit and lockout, per account and per IP.~~ Done ([0022](0022-login-throttling.md)); a shared store
  if the backend ever runs more than one instance.
- A full CSP with the hash of Flutter's inline loader.
- An `HttpOnly` refresh-cookie design (own ADR).
- ~~User management (0017).~~ Done ([0024](0024-admin-user-management.md)).

### Maintainability

**Done**
- Package-by-feature on both sides with a small shared kernel ([0002](0002-bounded-contexts-and-package-by-feature.md)).
- Data-ownership rules ([0003](0003-data-ownership-and-cross-context-contracts.md)) and layering
  ([0005](0005-layered-architecture-and-solid-conventions.md)).
- Zero javac warnings with `-Xlint:all` (2026-11-07), and `flutter analyze` with 0 issues and
  `dart format --set-exit-if-changed` in CI.
- Constructor injection everywhere, including the tests.
- One error format and one page wrapper.
- `docs/API_CONTRACT.md`, `docs/ER_DIAGRAM.md` and these ADRs.
- 2026-11-07 refactors:
  - `VehicleUsage` replaced native SQL against foreign tables;
  - the frontend vehicle/driver counter cycle was removed;
  - shared `SortMenu`, `RowActions`, `DataTableFrame` and `parseApiEnum` replaced copies;
  - `AssignmentService.assign` was split into short methods.

**Accepted limitations**
- The package cycle `vehicle ↔ assignment ↔ driver`, with entities crossing it (SOLID-BE-03, see 0003).
- `StorageService` implementations differ in transactional behaviour (SOLID-BE-06, see 0013).
- Constraint-race → 409 mapping exists twice (NFR-MAINT-01, see 0005).
- Frontend duplication and placement:
  - paging and sort logic is duplicated in three query controllers and two `*Actions` (SOLID-FE-07);
  - maintenance paging lives in widget state (SOLID-FE-06);
  - the dashboard navigates to other features in two different ways (SOLID-FE-12).
- Boundaries and layers are conventions with no architecture test.

**Follow-ups**
- ArchUnit or Spring Modulith verification.
- Narrow assignment ports.
- One constraint-race mechanism.
- A generic paged-query controller on the frontend.

### Reliability

**Done**
- Validation on the client and on the server, with database constraints as the last line of defence:
  - unique keys, `CHECK`s for enums, cost ≥ 0 and expiry ≥ issue, the partial unique indexes, and `RESTRICT`
    FKs;
  - each service rule has a database twin where SQL can express it (BR-01, BR-04, BR-06, BR-07, BR-10).
- Multi-step changes run in one transaction:
  - assign closes the previous assignment and opens the new one;
  - INACTIVE closes the current assignment;
  - an upload stores the bytes and the metadata, and removes the stored object if the metadata insert fails.
- Row locks on vehicle and driver for PUT, status change, assign and unassign ([0014](0014-driver-vehicle-assignment-model.md)).
- The dashboard reads one REPEATABLE READ snapshot, so its numbers agree with each other (0019, 2026-11-07).
- Date rules use an injected Asia/Dhaka `Clock` ([0011](0011-time-and-expiry-with-injected-clock.md)).
- Database and storage outages become 503 Problem Details (0008).

**Accepted limitations**
- **No optimistic locking** on vehicles, drivers and maintenance records (no `@Version`, no `ETag`/`If-Match`).
  When two users edit the same record, the last PUT silently wins (NFR-REL-01). *(Closed in v2.1 by
  [0023](0023-optimistic-locking.md): `@Version` on vehicles, drivers, maintenance records and users, `version`
  required in every PUT, 409 `stale-version` with a "Reload" dialog in the client.)*
- With `supabase` storage, `DocumentService.delete` removes the object before the commit. A failed commit leaves a
  row without a file (SOLID-BE-06).
- A FK `RESTRICT` violation from a race is the generic 409 `conflict`, not `*-has-history` (0012).

**Follow-ups**
- ~~`@Version` and `If-Match` for edits.~~ Done with a `version` field in the body ([0023](0023-optimistic-locking.md);
  `ETag`/`If-Match` was considered there and not chosen).
- After-commit deletion of external objects.
- Map the FK names to the `*-has-history` 409s.

### Other section 6 attributes (short)

- **Testability.**
  - Services are unit-tested with Mockito and a fixed `Clock`; APIs are tested with MockMvc against a real
    embedded PostgreSQL; frontend tests use provider overrides with fakes.
  - Backend line coverage is about 97 % (`stark-vms-backend/README.md`, JaCoCo). There is no coverage threshold
    in the build. See [0020](0020-test-strategy-and-ci.md).
- **Observability.**
  - `RequestLoggingFilter` logs one line per request (`method`, `path`, `status`, `durationMs`) and never logs
    query strings or bodies; `/actuator/health` is the only actuator endpoint.
  - Accepted limitation (NFR-MAINT-03): the logs are plain key=value text with no request id, user or client
    IP, and no JSON format. The guideline asks for "structured logs with request path and status"; path and
    status are there, but the format is not machine-structured.
  - Follow-up: JSON logs with a request id in the MDC, returned in Problem Details.
- **Usability.**
  - Responsive breakpoints: cards below 600 px, a compact table up to 1024 px, a full table above.
  - Every data screen has loading, data, empty and error (retry) states.
  - Badges always pair colour with text.
- **Portability.**
  - All configuration comes from environment variables (backend) or `--dart-define` (frontend); the same image
    runs locally and on Render ([0016](0016-deployment-render-and-netlify.md)).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Design for horizontal scale now (cache, message broker, read replicas) | The free tier has one instance and one database. Measured latency is a quarter of the budget, so there is nothing to scale for yet. |
| Treat the NFRs as satisfied by design, without measuring | The guideline gives a number (500 ms); `docs/PERFORMANCE.md` and the asserting test turn it into evidence that cannot silently regress. |
| Fix every review finding before release | Several fixes (optimistic locking, rate limiting, refresh tokens) need contract or schema changes and their own ADRs. They are recorded here and in the relevant ADRs as follow-ups instead of being half-done. |

## Consequences

**Positive**
- Every NFR has evidence or an explicit, named limitation. A reviewer can see what was measured and what was not.
- The follow-ups form a backlog ordered by risk: login rate limit, optimistic locking, after-commit storage
  delete, bootstrap race, structured logs.

**Negative / accepted trade-offs**
- The known gaps above remain in v2.0 and must be explained in a review; v2.1 closes login throttling, optimistic
  locking and user management (0022–0024).

**Follow-ups**
- Each follow-up that changes the API, the schema or security gets its own ADR before implementation
  ([0001](0001-record-architecture-decisions.md)).

## Related

- Guideline: sections 6, 13, 14.2, 15, 18.4, 21.
- ADRs: 0002, 0003, 0005, 0007, 0008, 0010, 0011, 0013, 0014, 0016, 0017, 0019, 0020, 0022, 0023, 0024.
- Files: `docs/PERFORMANCE.md`, `stark-vms-backend/src/test/java/com/starkgroup/vms/ListEndpointsPerformanceTest.java`,
  `stark-vms-backend/src/main/resources/application.yml`, `stark-vms-backend/Dockerfile`, `stark-vms-backend/render.yaml`,
  `BE/common/web/RequestLoggingFilter.java`, `BE/auth/AdminBootstrap.java`, `FE/bootstrap.dart`,
  `FE/core/config/app_config.dart`, `stark-vms-frontend/netlify.toml`, `stark-vms-backend/README.md`
  (Known limitations), `stark-vms-frontend/README.md` (Security notes, Known limitations).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
