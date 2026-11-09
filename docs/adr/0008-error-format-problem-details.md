# 0008. Error format: RFC 9457 Problem Details everywhere

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v1.0 was built; v2.0
  added its error `type` values, 413, and on 2026-11-07 the 503 mappings for database and storage failures)

## Context

Guideline 11.1: "Errors use one format everywhere: Problem Details (RFC 9457), built in to Spring Boot 3 via
ProblemDetail". The Flutter form must show a server error under the right field (guideline 14.1, FR-V05: the
duplicate-number 409 belongs to `vehicleNumber`). Section 13: error responses never contain stack traces.

Errors come from many places, and by default each produces a different body:
- controller validation;
- the service rules;
- database constraint races;
- the security filters (401/403, which run before Spring MVC);
- the multipart parser (413);
- the servlet container (`/error`, for example a URL rejected by the firewall).

## Decision

Every error response of the API is `application/problem+json` with `type`, `title`, `status`, `detail` and
`instance`. Validation errors, duplicates and single-field business-rule conflicts add an `errors` array of
`{field, message}`.

- **Type URIs:** `BE/common/exception/ApiErrorType.java` defines stable `type` URIs under
  `https://stark-vms/errors/`:
  - v1.0: `validation`, `duplicate-vehicle-number`, `not-found`, `unauthorized`, `forbidden`, `bad-request`,
    `conflict`, `internal`;
  - v2.0: `vehicle-has-history`, `driver-has-history`, `duplicate-driver-phone`, `duplicate-driver-license`,
    `assignment-not-allowed`, `driver-already-assigned`, `no-current-driver`, `file-too-large`.
- **The client's contract is `type` (and `errors[].field`).** `title` and `detail` are for people.
- **`BE/common/exception/ProblemDetails.java`** is the only factory: `of(...)`, and `withErrors(...)` which sets
  the `errors` property.
- **`GlobalExceptionHandler`** (extends `ResponseEntityExceptionHandler`) maps:
  - typed exceptions: `NotFoundException` 404, `DuplicateResourceException` 409 + `errors`,
    `BusinessRuleException` 409 (with `errors` when it names a field), `FieldValidationException` and Bean
    Validation 400 + `errors`, `BadRequestException` 400, `InvalidCredentialsException` 401, file too large 413;
  - `DataIntegrityViolationException`, through the registered `UniqueConstraintRule`s, to the same 409 as the
    service check (otherwise a generic 409 `conflict`);
  - database unreachable (`CannotCreateTransactionException`, `DataAccessResourceFailureException`) to 503
    "Service unavailable", and `StorageException` to 503 "Storage unavailable". Both log one WARN line with no
    stack trace;
  - anything else to 500 `internal` with a generic detail. This is logged at ERROR with the stack trace, and
    the stack trace goes only to the log.
- **Errors outside Spring MVC:**
  - `RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403) write Problem Details through
    `BE/common/web/ProblemResponseWriter.java`;
  - `BE/common/web/ProblemDetailsErrorController.java` replaces `/error`.
- **Configuration:** `spring.mvc.problemdetails.enabled: true`, and `server.error.include-stacktrace`,
  `include-message`, `include-binding-errors`, `include-exception` set to never/false (`application.yml`).
- **Frontend:** `FE/core/network/api_exception.dart` parses every error into an `ApiException` (`status`, `type`,
  `title`, `detail`, `errors[]` → `fieldErrors`). `FE/core/network/form_errors.dart` (`FormErrors.fromApi`) maps
  field errors, and known `type`s such as `duplicate-vehicle-number` → `vehicleNumber`, onto form fields.
- The contract is `docs/API_CONTRACT.md` §3 (shape, type list, status table) and §6.8 (v2.0 types).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Spring Boot's default error JSON (`timestamp`, `status`, `error`, `path`) | Not RFC 9457; no field errors; differs between MVC and filter errors. |
| A custom `{code, message}` envelope | The guideline names RFC 9457; Spring 6 supports `ProblemDetail` natively. |
| Error codes in `title` instead of `type` URIs | Titles are human text and may change; `type` is the documented machine key. |
| Separate `type` for 503 (e.g. `service-unavailable`) | Clients treat all 5xx alike ("try again"); `internal` with distinct titles keeps the type list unchanged (decision of 2026-11-07). |
| Return the exception message for 500s | Leaks internals (SQL, class names); against guideline 13. |

## Consequences

**Positive**
- One parser on the frontend. Every screen shows server field errors in the right place and a readable message
  otherwise.
- The race between two users registering the same number gives the same 409 as the normal check (TC-02, TC-03).
- Infrastructure outages are distinguishable from bugs (503 vs 500) and do not flood the log with stack traces.

**Negative / accepted trade-offs**
- `type` URIs use the non-resolvable host `stark-vms`. They are identifiers, not documentation links.
- Constraint names and their 409 messages exist both in the services and in the `UniqueConstraintRule` beans
  (finding NFR-MAINT-01, see 0005).
- `common/exception/GlobalExceptionHandler` imports `document.storage.StorageException`, so the shared kernel
  depends on a context (see 0003).
- Error logs have no request id, so a client error cannot be correlated with its server log line (NFR-MAINT-03,
  see 0015).

**Follow-ups**
- Move `StorageException` (or its handler) so `common` has no context import.
- Add a request id (MDC) that is logged and returned as a Problem Details property.

## Related

- Guideline: sections 11.1, 11.2, 13, 14.1, FR-V05, TC-02, TC-03, TC-05.
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0009](0009-vehicle-number-normalization-and-duplicate-check.md).
- Files: `BE/common/exception/*`, `BE/common/web/ProblemDetailsErrorController.java`,
  `BE/common/web/ProblemResponseWriter.java`, `BE/auth/RestAuthenticationEntryPoint.java`,
  `BE/auth/RestAccessDeniedHandler.java`, `stark-vms-backend/src/main/resources/application.yml`,
  `FE/core/network/api_exception.dart`, `FE/core/network/form_errors.dart`, `docs/API_CONTRACT.md` §3, §6.8.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`, `FE/` = `stark-vms-frontend/lib/`.
