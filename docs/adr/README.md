# Architecture Decision Records — Stark VMS v2.0 (Assignment 2)

This folder records the significant architecture decisions of Stark VMS, one Markdown file per decision, in the
Michael Nygard format with the MADR sections "Alternatives considered" and "Related"
([0001](0001-record-architecture-decisions.md)).

> **ADRs 0001–0021 were written after the code.** v1.0 and v2.0 were designed and built first; those ADRs were
> reconstructed from the code, the READMEs and the API contract on 2026-11-07, and every one says
> "Recorded: retroactively" in its header. They explain the system as it is; they are not proof of up-front
> design.
>
> **ADRs 0022–0024 (v2.1 hardening) were written before implementation**, on 2026-11-07, and say
> "Recorded: before implementation" in their header. They were implemented on 2026-11-08 (v2.1); a security review
> the same day led to an **"Amendment after the security review"** section in 0022 and 0024 (client IP from the
> right-most proxy-added entry, attempts reserved before the password check, locks never evicted, log-safe keys,
> millisecond token cut-off, tokens revoked on disable via migration V8, wrong current passwords throttled). Their
> "Files (planned)" lists are the intended change; the amendments name the real files and tests.
>
> **ADR 0025 (visual identity) was written after implementation**, on 2026-11-10, when the restyle was finished;
> its header says so. It should have come first.
>
> **From now on, every significant decision gets an ADR *before* it is implemented.** Copy
> [`template.md`](template.md), set `Status: Proposed`, open it with (or before) the pull request, and change it
> to `Accepted` when the change is merged. "Significant" means: a new dependency or external service, a new
> bounded context or a change of table ownership, an API contract change that is not purely additive, a security
> or data-retention rule, or anything hard to undo. An accepted ADR is never rewritten; a new ADR supersedes it.

The numbers match the ADRs of Assignment 1 (`../../../assignment 1/docs/adr/`): the same decision has the same
number in both folders. ADRs marked **new in v2.0** do not exist in Assignment 1; ADRs marked **extended in v2.0**
exist there with the v1.0 scope.

## Index

| No. | Decision | Status | In v1.0 (Assignment 1)? |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions (this process) | Accepted | yes |
| [0002](0002-bounded-contexts-and-package-by-feature.md) | Bounded contexts and package-by-feature | Accepted | extended in v2.0 (5 new contexts) |
| [0003](0003-data-ownership-and-cross-context-contracts.md) | Data ownership and cross-context contracts | Accepted | extended in v2.0 (cross-context rules, `VehicleUsage`) |
| [0004](0004-frontend-state-management-with-riverpod.md) | Frontend state management with Riverpod | Accepted | extended in v2.0 (more counters and query controllers) |
| [0005](0005-layered-architecture-and-solid-conventions.md) | Layered architecture and SOLID conventions | Accepted | extended in v2.0 (`StorageService`, `VehicleUsage`) |
| [0006](0006-postgresql-everywhere-flyway-and-validate.md) | PostgreSQL everywhere, Flyway, `ddl-auto=validate`, embedded PostgreSQL for tests | Accepted | yes (V1–V2; v2.0 adds V3–V6) |
| [0007](0007-authentication-stateless-jwt.md) | Authentication: stateless JWT, BCrypt, roles with `@PreAuthorize` | Accepted (v2.1 closing notes → 0022, 0024) | yes |
| [0008](0008-error-format-problem-details.md) | Error format: RFC 9457 Problem Details everywhere | Accepted | yes (v2.0 adds its error types) |
| [0009](0009-vehicle-number-normalization-and-duplicate-check.md) | Vehicle number normalization and duplicate check (service + DB constraint) | Accepted | yes (v2.0 reuses it for drivers) |
| [0010](0010-server-side-pagination-filtering-sorting.md) | Server-side pagination, filtering and sorting | Accepted | extended in v2.0 (filters, more lists) |
| [0011](0011-time-and-expiry-with-injected-clock.md) | Time and expiry: injected `Clock` in Asia/Dhaka, expiry computed not stored | Accepted | partly (v1.0: Clock for JWT, audit, year bound; expiry is new in v2.0) |
| [0012](0012-delete-policy-br-10.md) | Delete policy BR-10: hard delete only without history, INACTIVE instead | Accepted | **new in v2.0** |
| [0013](0013-document-file-storage-behind-storageservice.md) | Document file storage behind `StorageService` (DB `BYTEA` default, Supabase optional) | Accepted | **new in v2.0** |
| [0014](0014-driver-vehicle-assignment-model.md) | Driver–vehicle assignment model: history table, partial unique indexes, row locks | Accepted | **new in v2.0** |
| [0015](0015-non-functional-requirements-strategy.md) | Non-functional requirements strategy | Accepted (updated 2026-11-08: throttling, optimistic locking and user management closed by 0022–0024) | extended in v2.0 (v2.0 scope and findings) |
| [0016](0016-deployment-render-and-netlify.md) | Deployment: Render (Docker) and Netlify, configuration only via environment variables | Accepted | yes (v2.0 adds the storage variables) |
| [0017](0017-user-account-management-scope.md) | User account management: accounts from start-up configuration only (known gap) | Superseded by [0024](0024-admin-user-management.md) | **new in v2.0** as an ADR (the same gap exists in v1.0 and is noted in its 0007) |
| [0018](0018-accepted-deviations-from-the-guideline.md) | Accepted deviations from the guideline | Accepted | **new in v2.0** |
| [0019](0019-dashboard-as-a-read-model.md) | Dashboard as a read model: one summary endpoint, aggregate queries, one snapshot | Accepted | **new in v2.0** |
| [0020](0020-test-strategy-and-ci.md) | Test strategy and continuous integration | Accepted | yes (v2.0 adds the performance test and more suites) |
| [0021](0021-demo-data-seeded-into-the-real-database.md) | Demo data seeded into the real database (local profile only) | Accepted | yes (v1.0 seeds vehicles only) |
| [0022](0022-login-throttling.md) | Login throttling: in-memory failure counters per e-mail and per IP, 429 with `Retry-After` | Accepted — **written before implementation** (v2.1), implemented 2026-11-08, **amended** after the security review | yes (same decision and amendment, v1.1) |
| [0023](0023-optimistic-locking.md) | Optimistic locking: `version` on vehicles, drivers, maintenance records and users, required in every PUT, 409 `stale-version` | Accepted — **written before implementation** (v2.1), implemented 2026-11-08 | yes (vehicles only, v1.1) |
| [0024](0024-admin-user-management.md) | ADMIN user management, own password change, tokens checked against the current user (supersedes 0017) | Accepted — **written before implementation** (v2.1), implemented 2026-11-08, **amended** after the security review | **new in v2.1** (not in v1.0) |
| [0025](0025-visual-identity-navy-and-gold.md) | Visual identity: navy and gold (one palette file, bundled Playfair Display for headings and figures, `AppDecorations` and `brand.dart`, gold never as text on a light surface) | Accepted — **written after implementation** (2026-11-10) | yes (same decision, same design files) |

## Reading order

- **New to the code:** 0002 → 0003 → 0005 → 0004.
- **Reviewing a feature:** the feature's own ADR (0009, 0012, 0013, 0014, 0019), then 0008 and 0010.
- **Reviewing quality and risk:** 0015 (every NFR with evidence and known gaps), then 0017 and 0018.
- **Reviewing the look and feel:** 0025, then `../../stark-vms-frontend/lib/core/theme/` and
  `../../stark-vms-frontend/lib/shared/widgets/brand.dart`.
- **Reviewing the v2.1 hardening:** 0022 (NFR-SEC-01), 0023 (NFR-REL-01), 0024 (supersedes 0017) — including the
  "Amendment after the security review" sections of 0022 and 0024 — then `../API_CONTRACT.md` Section 7.

## Conventions

- File name `NNNN-kebab-case-title.md`; numbers are never reused.
- Status values: `Proposed`, `Accepted`, `Rejected`, `Deprecated`, `Superseded by NNNN`.
- Paths in the ADRs are relative to the assignment folder. Short prefixes are explained at the end of each ADR:
  `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
  `MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
- Finding IDs such as `SOLID-BE-03` or `NFR-SEC-01` refer to the architecture and NFR review of 2026-11-07. The
  ones still open are listed in [0015](0015-non-functional-requirements-strategy.md) and in the ADR they belong to.

## Related documents

- [`../API_CONTRACT.md`](../API_CONTRACT.md): the frontend/backend contract (Section 6 is v2.0, Section 7 is the
  v2.1 hardening of 0022–0024, implemented).
- [`../ER_DIAGRAM.md`](../ER_DIAGRAM.md): all tables of migrations V1–V8 (plus V2_1).
- [`../PERFORMANCE.md`](../PERFORMANCE.md): measured performance (used by 0010, 0015, 0019).
- [`../../stark-vms-backend/README.md`](../../README.md) and
  [`../../stark-vms-frontend/README.md`](https://github.com/AvishekBis/stark-vms-frontend/blob/main/README.md): "Decisions and trade-offs" summaries.
