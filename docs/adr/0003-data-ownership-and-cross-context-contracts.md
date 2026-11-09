# 0003. Data ownership and cross-context contracts

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built)

## Context

[0002](0002-bounded-contexts-and-package-by-feature.md) splits the system into contexts. They share one PostgreSQL
database and one JVM, so without rules any service could read or write any table. The frontend and the backend
are separate projects (guideline section 9: two repositories) and need a stable, written contract. Guideline
section 9 says "JPA entities never leave the service layer. Controllers receive and return DTOs".

## Decision

### 1. Every table has exactly one owning context

| Table | Migration | Owning context (entity) | Foreign keys |
|---|---|---|---|
| `users` | `MIG/V1__create_users.sql` | `auth` (`BE/auth/User.java`) | — |
| `vehicles` | `MIG/V2__create_vehicles.sql` | `vehicle` (`BE/vehicle/Vehicle.java`) | — |

- Only the owning context writes its table, and only the owner adds migrations that change it.
- Both tables carry `created_at`, `updated_at` and `created_by` through `BE/common/persistence/AuditableEntity.java`.
- `vehicles.created_by` holds the creator's e-mail as text, not a FK to `users` (`docs/ER_DIAGRAM.md`).

### 2. How another context may read owned data

These rules are written for v2.0, when more contexts arrive. In order of preference:

- **(a) Owner's service.** Call a public method of the owning context's service.
- **(b) Port owned by the consumer.** The consumer declares an interface, and each owner implements it over its
  own repository.
- **(c) Root aggregate reference.** Read-only use of the vehicle repository or entity, for an existence check, a
  reference or a row lock.

Forbidden:
- native SQL against another context's tables;
- writing another context's table;
- the shared kernel (`common/`) importing a business context;
- an entity in a controller signature or a response DTO.

Allowed directions:
- every context → `common` / `config`;
- `config` → `auth` (wiring only);
- `dev` → any context (local profile only).

### 3. The API contract is the frontend/backend contract

- `docs/API_CONTRACT.md` is the source of truth for every path, JSON shape, enum, status code and Problem Details
  `type`:
  - §1 Auth;
  - §2 Vehicles;
  - §3 Error format;
  - §4 Other (health, Swagger, CORS, gzip);
  - §5 Demo accounts.
- All paths are under `/api/v1`, JSON is camelCase and dates are ISO-8601.
- Changes are additive and backward compatible.
- The OpenAPI document generated from the code (`/v3/api-docs`, `BE/config/OpenApiConfig.java`) and
  `stark-vms-backend/docs/postman_collection.json` must agree with it.
- The frontend mirrors each response DTO with a `fromJson` model in `FE/features/*/data/`.

### 4. DTOs only at the edges

- Request and response DTOs are Java records: `LoginRequest`, `LoginResponse`, `UserResponse`, `VehicleRequest`
  (interface), `CreateVehicleRequest`, `UpdateVehicleRequest` and `VehicleResponse`.
- `VehicleMapper` converts between them and the entity. No controller imports a repository or returns an entity.

### Every existing cross-context dependency

Backend (`common` imports excluded):

| From → to | Where | Follows the rule? |
|---|---|---|
| config → auth | `SecurityConfig` wires `JwtAuthenticationFilter`, `JwtService`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`; `JpaAuditingConfig` reads `AuthenticatedUser` for `created_by` | Yes (wiring) |
| dev → vehicle | `DemoDataSeeder` writes `Vehicle` rows through `VehicleRepository` (with `VehicleNumber`) | Accepted exception: only with `app.seed-demo-data=true` (local profile) |
| auth ↔ vehicle | none | — |

Frontend (`core/` and `shared/` excluded unless noted):

| From → to | Where | Follows the rule? |
|---|---|---|
| vehicles → auth | `vehicle_providers.dart` (query resets on user change), `vehicle_list_screen.dart`, `vehicle_detail_screen.dart` (`isAdmin` hides Delete) | Yes |
| dashboard → vehicles | `dashboard_providers.dart` calls `vehicleRepositoryProvider.list(...)` and watches `vehicleChangesProvider`; `dashboard_screen.dart` uses the `Vehicle` model and `VehicleStatusBadge` | **Partly.** Calling another feature's repository is a deviation, accepted because the v1.0 dashboard has no endpoint of its own. v2.0 replaces it with `GET /dashboard/summary` |
| dashboard → auth | `dashboard_screen.dart` reads `currentUserProvider` | Yes |
| shared → auth | `shared/widgets/app_shell.dart` reads the user and calls logout | **No**: the shared kernel depends on a feature. Tolerated, because the shell is the signed-in frame |
| core/router → all features | `app_router.dart` imports every screen | Yes (composition root) |

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Any service may use any repository | Works for two tables, but sets the wrong habit for v2.0. |
| FK `vehicles.created_by → users` | Audit is a text trail (the e-mail, or `system` for the seeder); a FK would couple the two contexts and block deleting users later. |
| Generated API client from OpenAPI | Adds a code generator to both builds; the hand-written contract plus a handful of models was enough. |
| Return entities from controllers | Guideline section 9 rule. It would leak `passwordHash` and cause lazy-loading errors, because `spring.jpa.open-in-view=false`. |

## Consequences

**Positive**
- Each table has one place where its rules live: BR-01 in `vehicle`, and the role check in `auth`.
- The frontend and backend agree through a written contract.

**Negative / accepted trade-offs**
- The rules are not machine-checked.
- The dashboard → vehicles repository call and the shared → auth import are known exceptions.

**Follow-ups**
- An architecture test for these rules (0002).
- Any contract change starts with a pull request to `docs/API_CONTRACT.md`, plus an ADR when the change is not
  additive.

## Related

- Guideline: sections 7, 9 (rule on entities), 10, 11.
- ADRs: [0002](0002-bounded-contexts-and-package-by-feature.md), [0005](0005-layered-architecture-and-solid-conventions.md),
  [0008](0008-error-format-problem-details.md).
- Files: `docs/API_CONTRACT.md`, `docs/ER_DIAGRAM.md`, `MIG/V1__create_users.sql`, `MIG/V2__create_vehicles.sql`,
  `BE/config/SecurityConfig.java`, `BE/config/JpaAuditingConfig.java`, `BE/dev/DemoDataSeeder.java`,
  `FE/features/dashboard/presentation/dashboard_providers.dart`, `FE/shared/widgets/app_shell.dart`.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
