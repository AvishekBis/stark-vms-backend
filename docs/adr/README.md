# Architecture Decision Records — Stark VMS v1.0 (Assignment 1)

This folder holds the significant architecture decisions of Stark VMS v1.0, one Markdown file per decision. The
format is Michael Nygard's, with the MADR sections "Alternatives considered" and "Related"
([0001](0001-record-architecture-decisions.md)).

> **ADRs 0001–0021 were written after the code.**
> - v1.0 was designed and built first.
> - On 2026-11-07 the ADRs were reconstructed from the code, the READMEs and the API contract.
> - Each one says "Recorded: retroactively" in its header.
> - They describe the system as it is. They are not proof that it was designed up front.
>
> **From now on, a significant decision gets an ADR *before* it is implemented.**
> - Copy [`template.md`](template.md) and set `Status: Proposed`.
> - Open it with the pull request, or before it, and set it to `Accepted` when the change is merged.
> - "Significant" means any of these:
>   - a new dependency or external service;
>   - a new bounded context, or a change in who owns a table;
>   - an API contract change that is not purely additive;
>   - a security or data-retention rule;
>   - anything that is hard to undo.
> - An accepted ADR is never rewritten. A new ADR supersedes it.
>
> **ADRs 0022–0023 (v1.1 hardening) were written before implementation**, on 2026-11-07, and say
> "Recorded: before implementation" in their header. They were **implemented on 2026-11-08**; each ADR ends with an
> "Implementation" section that lists the files and tests (the decisions themselves are unchanged).
>
> **ADR 0025 (visual identity) was written after implementation**, on 2026-11-10, when the restyle was finished;
> its header says so. It should have come first.

The numbers match the ADRs of Assignment 2 (v2.0, `assignment 2/docs/adr/`), so the same decision has the same
number in both folders. The gaps are deliberate. These decisions exist only in v2.0:
- 0012 delete policy BR-10;
- 0013 document storage;
- 0014 driver–vehicle assignment;
- 0017 user-management scope;
- 0018 accepted deviations;
- 0019 dashboard read model;
- 0024 ADMIN user management (v2.1; v1.0 keeps the user-management gap described in its 0007).

The ADRs 0001–0021 here describe only v1.0 code. 0022, 0023 and 0025 are the same decisions as in Assignment 2,
adapted to v1.0 (0023 covers vehicles only; 0025 covers the v1 screens, with the same design files).

## Index

| No. | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions (this process) | Accepted |
| [0002](0002-bounded-contexts-and-package-by-feature.md) | Bounded contexts and package-by-feature (`auth`, `vehicle`; `auth`, `vehicles`, `dashboard`) | Accepted |
| [0003](0003-data-ownership-and-cross-context-contracts.md) | Data ownership and cross-context contracts (`docs/API_CONTRACT.md`, DTOs at the edges) | Accepted |
| [0004](0004-frontend-state-management-with-riverpod.md) | Frontend state management with Riverpod (session, change counter, router guard) | Accepted |
| [0005](0005-layered-architecture-and-solid-conventions.md) | Layered architecture and SOLID conventions | Accepted |
| [0006](0006-postgresql-everywhere-flyway-and-validate.md) | PostgreSQL everywhere, Flyway, `ddl-auto=validate`, embedded PostgreSQL for tests | Accepted |
| [0007](0007-authentication-stateless-jwt.md) | Authentication: stateless JWT, BCrypt, roles with `@PreAuthorize` (incl. the user-management gap) | Accepted |
| [0008](0008-error-format-problem-details.md) | Error format: RFC 9457 Problem Details everywhere | Accepted |
| [0009](0009-vehicle-number-normalization-and-duplicate-check.md) | Vehicle number normalization and duplicate check (service + DB constraint) | Accepted |
| [0010](0010-server-side-pagination-filtering-sorting.md) | Server-side pagination, search and sorting | Accepted |
| [0011](0011-time-and-expiry-with-injected-clock.md) | Time: one injected `Clock` in Asia/Dhaka (JWT, audit, manufacture-year bound) | Accepted |
| [0015](0015-non-functional-requirements-strategy.md) | Non-functional requirements strategy (v1.0 scope) | Accepted |
| [0016](0016-deployment-render-and-netlify.md) | Deployment: Render (Docker) and Netlify, configuration only via environment variables | Accepted |
| [0020](0020-test-strategy-and-ci.md) | Test strategy and continuous integration | Accepted |
| [0021](0021-demo-data-seeded-into-the-real-database.md) | Demo data seeded into the real database (local profile only) | Accepted |
| [0022](0022-login-throttling.md) | Login throttling: in-memory failure counters per e-mail and per IP, 429 with `Retry-After` | Accepted — **written before implementation** (v1.1), implemented 2026-11-08, **amended** after the security review of 2026-11-08 (client IP from the right-most proxy-added entry, attempts reserved before the password check, locks never evicted, log-safe keys) |
| [0023](0023-optimistic-locking.md) | Optimistic locking: `version` on vehicles, required in `PUT /vehicles/{id}`, 409 `stale-version` | Accepted — **written before implementation** (v1.1), implemented 2026-11-08 |
| [0025](0025-visual-identity-navy-and-gold.md) | Visual identity: navy and gold (one palette file, bundled Playfair Display for headings and figures, `AppDecorations` and `brand.dart`, gold never as text on a light surface) | Accepted — **written after implementation** (2026-11-10) |

## Reading order

- **New to the code:** read 0002, then 0003, 0005 and 0004.
- **Reviewing the duplicate rule:** read 0009, then 0008.
- **Reviewing quality and risk:** read 0015.
- **Reviewing the look and feel:** read 0025, then `../../stark-vms-frontend/lib/core/theme/` and
  `../../stark-vms-frontend/lib/shared/widgets/brand.dart`.
- **Reviewing the v1.1 hardening:** read 0022 (NFR-SEC-01) and 0023 (NFR-REL-01), then `../API_CONTRACT.md`
  Section 6.

## Conventions

- File names are `NNNN-kebab-case-title.md`. Numbers are never reused.
- Status values are `Proposed`, `Accepted`, `Rejected`, `Deprecated` and `Superseded by NNNN`.
- Paths are relative to the assignment folder. Short prefixes are explained at the end of each ADR:
  - `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`;
  - `MIG/` = `stark-vms-backend/src/main/resources/db/migration/`;
  - `FE/` = `stark-vms-frontend/lib/`.
- Finding IDs such as `NFR-SEC-01` refer to the architecture and NFR review of 2026-11-07.

## Related documents

- [`../API_CONTRACT.md`](../API_CONTRACT.md): the frontend/backend contract (Section 6 is the v1.1
  hardening of 0022–0023, implemented 2026-11-08).
- [`../ER_DIAGRAM.md`](../ER_DIAGRAM.md): `users` and `vehicles` (migrations V1, V2 and V2_1).
- [`../PERFORMANCE.md`](../PERFORMANCE.md): measured performance, used by 0010 and 0015.
- [`../../stark-vms-backend/README.md`](../../README.md) and
  [`../../stark-vms-frontend/README.md`](https://github.com/AvishekBis/stark-vms-frontend/blob/main/README.md): their "Decisions and trade-offs" sections
  summarise these ADRs.
