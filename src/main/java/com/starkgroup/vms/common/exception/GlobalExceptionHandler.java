package com.starkgroup.vms.common.exception;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import com.starkgroup.vms.document.storage.StorageException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one central error handler: every error leaves the API as RFC 9457 Problem Details
 * (API contract section 3). Stack traces are never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_TITLE = "Validation failed";
    private static final String VALIDATION_DETAIL = "One or more fields are invalid.";
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    /** Unique constraints that are also checked in a service, mapped to the same 409 the service returns. */
    private final List<UniqueConstraintRule> uniqueConstraintRules;

    public GlobalExceptionHandler(List<UniqueConstraintRule> uniqueConstraintRules) {
        this.uniqueConstraintRules = List.copyOf(uniqueConstraintRules);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return respond(ProblemDetails.of(HttpStatus.NOT_FOUND, ApiErrorType.NOT_FOUND, "Resource not found",
                ex.getMessage()), request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ProblemDetail> handleDuplicate(DuplicateResourceException ex, HttpServletRequest request) {
        return respond(duplicateProblem(ex), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ProblemDetail> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail problem = ex.getField() == null
                ? ProblemDetails.of(HttpStatus.CONFLICT, ex.getType(), ex.getTitle(), ex.getMessage())
                : ProblemDetails.withErrors(HttpStatus.CONFLICT, ex.getType(), ex.getTitle(), ex.getMessage(),
                        List.of(new FieldErrorResponse(ex.getField(), ex.getMessage())));
        return respond(problem, request);
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ProblemDetail> handleFieldValidation(FieldValidationException ex,
                                                               HttpServletRequest request) {
        String detail = ex.getDetail() == null ? VALIDATION_DETAIL : ex.getDetail();
        return respond(ProblemDetails.withErrors(HttpStatus.BAD_REQUEST, ApiErrorType.VALIDATION, VALIDATION_TITLE,
                detail, List.of(new FieldErrorResponse(ex.getField(), ex.getMessage()))), request);
    }

    /** ADR 0023: the version sent by the client is not the stored one. No errors[]: the whole record changed. */
    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<ProblemDetail> handleStaleVersion(StaleVersionException ex, HttpServletRequest request) {
        return respond(staleVersionProblem(ex.getMessage()), request);
    }

    /**
     * ADR 0023: a concurrent commit won between the service's version check and the flush (Hibernate's versioned
     * UPDATE matched no row). Same 409 as {@link StaleVersionException}; the record noun comes from the entity class
     * name ("MaintenanceRecord" becomes "maintenance record"), so this handler needs no feature imports.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex,
                                                                        HttpServletRequest request) {
        log.info("Optimistic locking conflict on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getPersistentClassName());
        return respond(staleVersionProblem(StaleVersionException.messageFor(recordNoun(ex.getPersistentClassName()))),
                request);
    }

    /** ADR 0022: e-mail locked or IP blocked after too many failed logins. 429 with Retry-After, no errors[]. */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex,
                                                                    HttpServletRequest request) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.TOO_MANY_REQUESTS, ApiErrorType.TOO_MANY_LOGIN_ATTEMPTS,
                TooManyLoginAttemptsException.TITLE, ex.getMessage());
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(FileTooLargeException.class)
    public ResponseEntity<ProblemDetail> handleFileTooLarge(FileTooLargeException ex, HttpServletRequest request) {
        return respond(fileTooLargeProblem(), request);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        return respond(ProblemDetails.of(HttpStatus.BAD_REQUEST, ApiErrorType.BAD_REQUEST, "Bad request",
                ex.getMessage()), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex,
                                                                  HttpServletRequest request) {
        return respond(ProblemDetails.of(HttpStatus.UNAUTHORIZED, ApiErrorType.UNAUTHORIZED, "Unauthorized",
                ex.getMessage()), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(ProblemDetails.of(HttpStatus.UNAUTHORIZED, ApiErrorType.UNAUTHORIZED, "Unauthorized",
                "Authentication is required to access this resource."), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(ProblemDetails.of(HttpStatus.FORBIDDEN, ApiErrorType.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action."), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        Optional<DuplicateResourceException> knownDuplicate = uniqueConstraintRules.stream()
                .filter(rule -> mentionsConstraint(ex, rule.constraintName()))
                .map(UniqueConstraintRule::toException)
                .findFirst();
        if (knownDuplicate.isPresent()) {
            return respond(duplicateProblem(knownDuplicate.get()), request);
        }
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return respond(ProblemDetails.of(HttpStatus.CONFLICT, ApiErrorType.CONFLICT, "Conflict",
                "The request conflicts with existing data."), request);
    }

    /**
     * Database not reachable or no pooled connection free in time (infrastructure, not a bug): 503 with a short
     * WARN line instead of a 500 with a stack trace.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
        log.warn("Database unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return respond(ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorType.INTERNAL, "Service unavailable",
                "The database is not reachable right now. Please try again shortly."), request);
    }

    /** File storage failed (stored bytes missing, Supabase down, unreadable upload): 503, no stack trace. */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ProblemDetail> handleStorage(StorageException ex, HttpServletRequest request) {
        log.warn("Storage failure on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return respond(ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorType.INTERNAL, "Storage unavailable",
                "The file storage is not available right now. Please try again later."), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorType.INTERNAL,
                "Internal server error", "An unexpected error occurred."), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> firstErrorPerField = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            firstErrorPerField.putIfAbsent(fieldError.getField(), messageOf(fieldError));
        }
        List<FieldErrorResponse> errors = firstErrorPerField.entrySet().stream()
                .map(entry -> new FieldErrorResponse(entry.getKey(), entry.getValue()))
                .toList();
        ProblemDetail problem = ProblemDetails.withErrors(HttpStatus.BAD_REQUEST, ApiErrorType.VALIDATION,
                VALIDATION_TITLE, VALIDATION_DETAIL, errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldErrorResponse> errors = ex.getParameterValidationResults().stream()
                .map(result -> new FieldErrorResponse(
                        Optional.ofNullable(result.getMethodParameter().getParameterName()).orElse("parameter"),
                        result.getResolvableErrors().getFirst().getDefaultMessage()))
                .toList();
        ProblemDetail problem = ProblemDetails.withErrors(HttpStatus.BAD_REQUEST, ApiErrorType.VALIDATION,
                VALIDATION_TITLE, VALIDATION_DETAIL, errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Multipart limit hit while Tomcat parses the request (spring.servlet.multipart.*) - the same 413 as the
     * explicit size check in the document upload (BR-07).
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        return handleExceptionInternal(ex, fileTooLargeProblem(), headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem;
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = fieldPath(mismatch);
            problem = ProblemDetails.withErrors(HttpStatus.BAD_REQUEST, ApiErrorType.VALIDATION, VALIDATION_TITLE,
                    VALIDATION_DETAIL, List.of(new FieldErrorResponse(field, invalidValueMessage(mismatch))));
        } else {
            problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, ApiErrorType.BAD_REQUEST, "Malformed request",
                    "The request body is missing or is not valid JSON.");
        }
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             @Nullable Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        Object problemBody = body;
        if (problemBody == null && ex instanceof ErrorResponse errorResponse) {
            problemBody = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (problemBody instanceof ProblemDetail problem) {
            if (problem.getType() == null || ABOUT_BLANK.equals(problem.getType())) {
                problem.setType(ApiErrorType.forStatus(statusCode));
            }
            if (problem.getInstance() == null && request instanceof ServletWebRequest servletRequest) {
                problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
            }
        }
        return super.handleExceptionInternal(ex, problemBody, headers, statusCode, request);
    }

    private static ProblemDetail staleVersionProblem(String detail) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ApiErrorType.STALE_VERSION, StaleVersionException.TITLE, detail);
    }

    /**
     * "com.starkgroup.vms.maintenance.MaintenanceRecord" becomes "maintenance record"; "record" when unknown.
     */
    static String recordNoun(@Nullable String persistentClassName) {
        if (persistentClassName == null || persistentClassName.isBlank()) {
            return "record";
        }
        String simpleName = persistentClassName.substring(persistentClassName.lastIndexOf('.') + 1);
        return simpleName.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").toLowerCase(Locale.ROOT);
    }

    private static ProblemDetail fileTooLargeProblem() {
        return ProblemDetails.of(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrorType.FILE_TOO_LARGE, "File too large",
                FileTooLargeException.MESSAGE);
    }

    /**
     * A type-conversion failure while binding form fields (e.g. a bad date or enum in a multipart upload) carries
     * Spring's internal conversion message; it is replaced by a short, safe one.
     */
    private static String messageOf(FieldError fieldError) {
        if (fieldError.isBindingFailure()) {
            return "Invalid value or wrong data type.";
        }
        return fieldError.getDefaultMessage();
    }

    private static ProblemDetail duplicateProblem(DuplicateResourceException ex) {
        return ProblemDetails.withErrors(HttpStatus.CONFLICT, ex.getType(), ex.getTitle(), ex.getMessage(),
                List.of(new FieldErrorResponse(ex.getField(), ex.getMessage())));
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem, HttpServletRequest request) {
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static boolean mentionsConstraint(DataIntegrityViolationException ex, String constraintName) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static String fieldPath(MismatchedInputException ex) {
        return ex.getPath().stream()
                .map(reference -> reference.getFieldName() != null
                        ? reference.getFieldName()
                        : "[" + reference.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static String invalidValueMessage(JsonMappingException ex) {
        if (ex instanceof MismatchedInputException mismatch && mismatch.getTargetType() != null
                && mismatch.getTargetType().isEnum()) {
            String allowed = Arrays.stream(mismatch.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return "Invalid value. Allowed values: " + allowed + ".";
        }
        return "Invalid value or wrong data type.";
    }
}
