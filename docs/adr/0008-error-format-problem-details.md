# 0008. Error format: RFC 9457 Problem Details everywhere

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; the 503
  mapping for an unreachable database was added on 2026-11-07)

## Context

Guideline 11.1 says: "Errors use one format everywhere: Problem Details (RFC 9457), built in to Spring Boot 3 via
ProblemDetail". The Flutter form must show a server error under the right field (guideline 14.1; FR-V05: the
duplicate-number 409 belongs to `vehicleNumber`). Section 13 says error responses never contain stack traces.

Errors come from many places, and by default each one produces a different body:
- controller validation;
- the service rules;
- database constraint races;
- the security filters (401/403), which run before Spring MVC;
- the servlet container (`/error`, for example a URL that the firewall rejects).

## Decision

Every error response of the API is `application/problem+json` with `type`, `title`, `status`, `detail` and
`instance`. Validation errors and duplicates add an `errors` array of `{field, message}`.

- **Type URIs.** `BE/common/exception/ApiErrorType.java` defines stable `type` URIs under
  `https://stark-vms/errors/`: `validation`, `duplicate-vehicle-number`, `not-found`, `unauthorized`,
  `forbidden`, `bad-request`, `conflict` and `internal`. `forStatus` picks the default for a status.
- **The client's contract is `type` (and `errors[].field`).** `title` and `detail` are for people.
- **One factory.** `BE/common/exception/ProblemDetails.java` provides `of(...)` and `withErrors(...)`.
- **`GlobalExceptionHandler`** (extends `ResponseEntityExceptionHandler`) maps:
  - typed exceptions:
    - `NotFoundException` → 404;
    - `DuplicateResourceException` → 409 with `errors`;
    - `BusinessRuleException` → 409;
    - `BadRequestException` → 400;
    - `InvalidCredentialsException` and `AuthenticationException` → 401;
    - `AccessDeniedException` → 403;
  - Bean Validation and unreadable or mistyped input → 400 `validation` with `errors`, or `bad-request`;
  - `DataIntegrityViolationException`, through the registered `UniqueConstraintRule`s, to the same 409 as the
    service check, and otherwise to a generic 409 `conflict`;
  - an unreachable database (`CannotCreateTransactionException`, `DataAccessResourceFailureException`) → 503
    "Service unavailable", logged as one WARN line without a stack trace;
  - anything else → 500 `internal` with a generic detail. The stack trace goes only to the log, at ERROR.
- **Errors outside Spring MVC.** `RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403) write
  Problem Details through `BE/common/web/ProblemResponseWriter.java`, and
  `BE/common/web/ProblemDetailsErrorController.java` replaces `/error`.
- **Configuration.** `spring.mvc.problemdetails.enabled: true`. `server.error.include-stacktrace`,
  `include-message`, `include-binding-errors` and `include-exception` are set to never/false (`application.yml`).
- **Frontend.**
  - `FE/core/network/api_exception.dart` parses every error into an `ApiException` (`status`, `type`, `title`,
    `detail`, `errors[]` → `fieldErrors`).
  - `VehicleFormController` puts the field errors, and the `duplicate-vehicle-number` type, under the matching
    form field.
- **The contract** is `docs/API_CONTRACT.md` §3: the shape, the type list and the status table.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Spring Boot's default error JSON (`timestamp`, `status`, `error`, `path`) | Not RFC 9457; no field errors; differs between MVC and filter errors. |
| A custom `{code, message}` envelope | The guideline names RFC 9457; Spring 6 supports `ProblemDetail` natively. |
| Error codes in `title` instead of `type` URIs | Titles are human text and may change. |
| A separate `type` for 503 | Clients treat every 5xx as "try again"; `internal` with its own title keeps the type list unchanged. |
| Return the exception message for 500s | Leaks internals; against guideline 13. |

## Consequences

**Positive**
- The frontend has one parser. The duplicate-number 409 appears under the number field (TC-02, TC-03), and a
  missing number gives 400 with a field error (TC-05).
- A race on the unique constraint gives the same 409 as the normal check.
- An outage is distinguishable from a bug (503 vs 500).

**Negative / accepted trade-offs**
- The `type` URIs use the non-resolvable host `stark-vms`. They are identifiers, not documentation links.
- The constraint name and its 409 message exist both in `VehicleService` and in `VehicleConstraintConfig` (0005).
- Error logs have no request id, so a client error cannot be matched to its log line.

**Follow-ups**
- A request id (MDC) that is logged and returned as a Problem Details property.

## Related

- Guideline: sections 11.1, 11.2, 13, 14.1, FR-V05, TC-02, TC-03, TC-05, TC-06, TC-07.
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0009](0009-vehicle-number-normalization-and-duplicate-check.md).
- Files: `BE/common/exception/*`, `BE/common/web/ProblemDetailsErrorController.java`,
  `BE/common/web/ProblemResponseWriter.java`, `BE/auth/RestAuthenticationEntryPoint.java`,
  `BE/auth/RestAccessDeniedHandler.java`, `stark-vms-backend/src/main/resources/application.yml`,
  `FE/core/network/api_exception.dart`, `FE/features/vehicles/presentation/vehicle_form_controller.dart`,
  `docs/API_CONTRACT.md` §3.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
