# 0005. Layered architecture and SOLID conventions

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; the
  `StorageService` and `VehicleUsage` interfaces were added in v2.0)

## Context

Guideline section 7 defines three backend layers, each allowed to call only the layer below:
- **Controller** maps HTTP to Java and must not talk to the repository.
- **Service** holds the business rules, transactions and entity ↔ DTO mapping, and must not know HTTP.
- **Repository** runs queries and must not contain rules or concatenated SQL.

Section 16.3 asks for constructor injection, records for DTOs and short methods. The maintainability NFR asks
for "no business logic in controllers or widgets" and zero compiler/analyzer warnings. Several parts of the
system have more than one real implementation or must be replaceable in tests: file storage (database or
Supabase) and "now" (the system clock in production, a fixed clock in tests).

## Decision

### Backend: Controller → Service → Repository

- **Controllers** (`BE/*/*Controller.java`):
  - read the request, validate the DTO with `@Valid` (and `@Min`/`@Max` on query parameters), call one service
    method, and return the status code and `Location` header;
  - document their responses with springdoc `@ApiResponse`;
  - import no repository and hold no `@Transactional`.
- **Services** (`BE/*/*Service.java`):
  - own every business rule and transaction (`@Transactional`, `readOnly = true` for reads);
  - map through a hand-written `*Mapper` and return DTOs;
  - never use `HttpServletRequest`/`HttpServletResponse`.
  - `@Transactional` appears only in services, `AdminBootstrap`, `DemoDataSeeder` and `DatabaseStorageService`.
- **Repositories** are Spring Data JPA interfaces with derived queries, JPQL `@Query`, `@EntityGraph` and
  `Specification`s, all parameter-bound. User input in `LIKE` is escaped by `BE/common/util/LikePatterns.java`.
- **Errors** are thrown as typed exceptions from `common/exception` and turned into Problem Details in one place
  ([0008](0008-error-format-problem-details.md)).

### Dependency injection

Constructor injection only, with `private final` fields. There is no field `@Autowired` in `src/main`, and since
2026-11-07 none in `src/test` either (API tests use an `@Autowired` constructor). Configuration is bound to
records (`JwtProperties`, `CorsProperties`, `BootstrapProperties`, `StorageProperties`).

### Interfaces only where variation exists

| Abstraction | Why it exists | Implementations |
|---|---|---|
| `BE/document/storage/StorageService` | Two real storage back ends, chosen by `app.storage.provider` | `DatabaseStorageService` (default), `SupabaseStorageService` — [0013](0013-document-file-storage-behind-storageservice.md) |
| `java.time.Clock` (bean in `BE/config/ClockConfig.java`) | Deterministic date rules in tests | `Clock.system(Asia/Dhaka)`; tests use `Clock.fixed(...)` — [0011](0011-time-and-expiry-with-injected-clock.md) |
| `BE/vehicle/VehicleUsage` | Each owning context answers BR-10 for its own data (dependency inversion) | `DocumentVehicleUsage`, `MaintenanceVehicleUsage`, `AssignmentVehicleUsage` — [0012](0012-delete-policy-br-10.md) |
| `UniqueConstraintRule` (record, collected as `List<UniqueConstraintRule>`) | The global handler maps constraint names to 409s without importing any context (open/closed) | Beans in `VehicleConstraintConfig`, `DriverConstraintConfig`, `AssignmentConstraintConfig` |

Services such as `VehicleService` and `ExpiryCalculator` are concrete classes and are mocked with Mockito where
needed. We do not add `FooService` / `FooServiceImpl` pairs.

### Frontend: Screen → Provider/Controller → Repository → ApiClient

Screens and widgets render state and forward actions. Rules live in:
- controllers;
- model getters, such as `Vehicle.canReceiveDriver`, `Vehicle.endsAssignmentOn` and `Driver.losesVehicleOn`
  in `FE/features/*/data/`;
- validators.

Each repository is an `abstract interface class` with an `Api*` implementation over the single `ApiClient`. Tests
replace it with a fake.

### SOLID guidelines for future code

- **S** One reason to change per class:
  - a controller maps HTTP, a service owns one context's rules, a mapper maps;
  - extract a collaborator when a service method needs a private helper of more than about 25 lines (as
    `AssignmentService.assign` was split into `requireAssignable`, `requireDriverFree` and `openAssignment`);
  - widgets must not hold paging or business rules.
- **O** Extend by adding beans, not by editing shared code:
  - a new unique constraint is a new `UniqueConstraintRule` bean;
  - a new record type that references vehicles is a new `VehicleUsage`;
  - a new storage back end is a new `StorageService` behind `app.storage.provider`.
- **L** An implementation must honour the documented contract of its interface, including transactional
  behaviour. Today `StorageService` does not state its transactional semantics, and the two implementations
  differ (finding SOLID-BE-06, see 0013). New interfaces must document this.
- **I** Depend on the narrowest interface that answers the question. `VehicleService` and `DriverService`
  depend on the whole `AssignmentService` but use only its read and close methods (finding SOLID-BE-03). New
  cross-context needs get a narrow port (0003, rule (b)).
- **D** A context that needs data from another declares the port it needs (as `VehicleUsage` does) instead of
  importing the other context's repository. Infrastructure (clock, storage, HTTP) is always injected.
- General rules:
  - no static access to "now";
  - no `new` of collaborators inside services;
  - no business logic in controllers or widgets;
  - `-Xlint:all` with zero javac warnings (`stark-vms-backend/pom.xml`) and `flutter analyze` with zero issues.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Hexagonal / ports-and-adapters for every context (domain model free of JPA) | Doubles the classes (domain object + JPA entity + mapper) for CRUD-heavy contexts. Ports are added only where a real boundary needs one (`StorageService`, `VehicleUsage`). |
| An interface for every service (`VehicleService` + `VehicleServiceImpl`) | No second implementation exists; Mockito mocks concrete classes. |
| Field injection (`@Autowired` on fields) | Against guideline 16.3; hides dependencies and blocks `final` fields and plain unit tests. |
| Mapping libraries (MapStruct, ModelMapper) | One more annotation processor or reflection layer; the mappers are small and hand-written mapping is explicit about computed fields (expiry status, current driver). |
| Business rules in the Flutter widgets | Against guideline 6; widget tests would have to cover rules. |

## Consequences

**Positive**
- Each layer is testable alone:
  - Mockito unit tests for services (`*ServiceTest`);
  - MockMvc API tests for the HTTP mapping (`*ApiTest`);
  - fakes for frontend repositories.
- Swapping storage or clock needs no change in callers.

**Negative / accepted trade-offs**
- The layering is a convention. No test fails if a controller imports a repository.
- Two mechanisms map unique-constraint races to 409, and the constraint names and messages are defined twice
  (finding NFR-MAINT-01):
  - the services catch `DataIntegrityViolationException` and match constraint names with `message.contains`
    (`VehicleService.saveAndFlush`, `DriverService`, `AssignmentService.isOpenAssignmentViolation`);
  - `GlobalExceptionHandler` does the same through the `UniqueConstraintRule` beans.
- The interface-segregation and Liskov issues named above remain.

**Follow-ups**
- Keep one mechanism for constraint races: the `UniqueConstraintRule` beans, with the services rethrowing.
- Document transactional semantics on `StorageService` (0013).
- Narrow `AssignmentService` ports (0003).
- Architecture test for the layer rules (0002).

## Related

- Guideline: sections 6 (Maintainability, Testability), 7, 9, 16.3.
- ADRs: [0002](0002-bounded-contexts-and-package-by-feature.md), [0003](0003-data-ownership-and-cross-context-contracts.md),
  [0011](0011-time-and-expiry-with-injected-clock.md), [0013](0013-document-file-storage-behind-storageservice.md).
- Files: `BE/*/*Controller.java`, `BE/*/*Service.java`, `BE/*/*Mapper.java`, `BE/document/storage/StorageService.java`,
  `BE/vehicle/VehicleUsage.java`, `BE/common/exception/UniqueConstraintRule.java`, `BE/config/ClockConfig.java`,
  `FE/features/*/data/*_repository.dart`, `stark-vms-backend/pom.xml` (compiler flags).

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
