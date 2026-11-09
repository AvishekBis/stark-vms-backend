# 0005. Layered architecture and SOLID conventions

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built)

## Context

Guideline section 7 defines three backend layers, each allowed to call only the layer below:
- the **Controller** maps HTTP to Java and must not talk to the repository;
- the **Service** holds the business rules, the transactions and the entity ↔ DTO mapping, and must not know HTTP;
- the **Repository** runs queries and must not contain rules or concatenated SQL.

Section 16.3 asks for constructor injection, records for DTOs and short methods. The maintainability NFR asks for
"no business logic in controllers or widgets" and zero compiler and analyzer warnings. "Now" must be replaceable in
tests (section 15).

## Decision

### Backend: Controller → Service → Repository

- **Controllers** (`BE/auth/AuthController.java`, `BE/vehicle/VehicleController.java`):
  - read the request and validate the DTO with `@Valid` (and `@Min`/`@Max` on query parameters);
  - call one service method;
  - return the status code and the `Location` header (201 on create);
  - document every response with springdoc `@ApiResponse`;
  - import no repository and hold no `@Transactional`.
- **Services** (`AuthService`, `VehicleService`):
  - own every rule and transaction (`@Transactional`, `readOnly = true` for reads);
  - map through `VehicleMapper` and return DTOs;
  - never use `HttpServletRequest` or `HttpServletResponse`.
- **Repositories** (`UserRepository`, `VehicleRepository`) are Spring Data JPA interfaces with derived queries and
  `Specification`s. Every value is bound as a parameter; `LIKE` input is escaped by `BE/common/util/LikePatterns.java`.
- **Errors** are thrown as typed exceptions from `common/exception` and turned into Problem Details in one place
  ([0008](0008-error-format-problem-details.md)).

### Dependency injection

- Constructor injection only, with `private final` fields. There is no field `@Autowired` in `src/main`, and since
  2026-11-07 none in `src/test` either (API tests use an `@Autowired` constructor).
- Configuration is bound to records (`JwtProperties`, `CorsProperties`, `BootstrapProperties`).

### Interfaces only where variation exists

| Abstraction | Why it exists | Implementations |
|---|---|---|
| `java.time.Clock` (bean in `BE/config/ClockConfig.java`) | Deterministic date rules in tests | `Clock.system(Asia/Dhaka)`; tests use `Clock.fixed(...)` ([0011](0011-time-and-expiry-with-injected-clock.md)) |
| `UniqueConstraintRule` (record, collected as `List<UniqueConstraintRule>`) | The global handler maps constraint names to 409s without importing any context (open/closed) | `VehicleConstraintConfig` |
| Frontend `abstract interface class AuthRepository` and `VehicleRepository` | Tests replace them with fakes | `ApiAuthRepository`, `ApiVehicleRepository` (`SessionStorage` is a concrete class over `SharedPreferences`, which tests replace through `sharedPreferencesProvider`) |

- Services are concrete classes and are mocked with Mockito where needed.
- We do not add `FooService` / `FooServiceImpl` pairs.

### Frontend: Screen → Provider/Controller → Repository → ApiClient

- Screens and widgets render state and forward actions.
- Rules live in controllers (`VehicleFormController`, `VehicleActions`), validators (`FE/core/utils/validators.dart`)
  and repositories.
- One `ApiClient` is used by all repositories.

### SOLID guidelines for future code

- **S — one reason to change per class.**
  - A controller maps HTTP, a service owns one context's rules, and a mapper maps.
  - Extract a collaborator when a method no longer reads at once.
- **O — extend by adding beans, not by editing shared code.**
  - A new unique constraint is a new `UniqueConstraintRule` bean in the owning context.
  - A new list filter is a new `Specification`.
- **L — implementations honour the documented contract of their interface,** including errors and transactional
  behaviour.
- **I — depend on the narrowest interface that answers the question.** When v2.0 needs data from another context,
  it gets a small port (0003, rule (b)), not the whole foreign service.
- **D — a context that needs data from another declares the port it needs.** Infrastructure (clock, HTTP, storage)
  is always injected.
- **General rules:**
  - no static access to "now";
  - no `new` of collaborators inside services;
  - no business logic in controllers or widgets;
  - `-Xlint:all` with zero javac warnings (`stark-vms-backend/pom.xml`), and `flutter analyze` with zero issues.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Hexagonal / ports-and-adapters for every context | Doubles the classes (domain object + JPA entity + mapper) for a CRUD-heavy v1.0. Ports are added when a real boundary needs one. |
| An interface for every service | No second implementation exists; Mockito mocks concrete classes. |
| Field injection | Against guideline 16.3; hides dependencies and blocks `final` fields and plain unit tests. |
| Mapping libraries (MapStruct, ModelMapper) | One more annotation processor or reflection layer; the one mapper is small and explicit. |
| Rules in Flutter widgets | Against guideline 6; widget tests would have to cover the rules. |

## Consequences

**Positive**
- Each layer is testable alone:
  - `VehicleServiceTest` (Mockito);
  - `VehicleApiTest` and `AuthApiTest` (MockMvc, real PostgreSQL);
  - frontend fakes.
- v2.0 could add contexts with the same shape.

**Negative / accepted trade-offs**
- The layering is a convention. No test fails if a controller imports a repository.
- A unique-constraint race is mapped to 409 in two places:
  - `VehicleService.saveAndFlush` (it matches the constraint name with `message.contains`);
  - `GlobalExceptionHandler`, through the `UniqueConstraintRule` bean.
  The constraint name and the message are defined twice.

**Follow-ups**
- Keep one mechanism for constraint races (the rule beans).
- An architecture test for the layer rules (0002).

## Related

- Guideline: sections 6 (Maintainability, Testability), 7, 9, 16.3.
- ADRs: [0002](0002-bounded-contexts-and-package-by-feature.md), [0003](0003-data-ownership-and-cross-context-contracts.md),
  [0008](0008-error-format-problem-details.md), [0011](0011-time-and-expiry-with-injected-clock.md).
- Files: `BE/*/*Controller.java`, `BE/*/*Service.java`, `BE/vehicle/VehicleMapper.java`,
  `BE/common/exception/UniqueConstraintRule.java`, `BE/config/ClockConfig.java`, `FE/features/*/data/*_repository.dart`,
  `stark-vms-backend/pom.xml` (compiler flags).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
