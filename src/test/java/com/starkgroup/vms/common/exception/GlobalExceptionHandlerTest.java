package com.starkgroup.vms.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Error mapping that the API tests cannot trigger on demand: a unique-constraint race reaching the
 * central handler, other integrity violations, unexpected exceptions, a lost optimistic-locking race (ADR 0023) and
 * the exact Retry-After rounding of a throttled login (ADR 0022).
 */
class GlobalExceptionHandlerTest {

    private static final UniqueConstraintRule VEHICLE_NUMBER_RULE = new UniqueConstraintRule(
            "uk_vehicles_number_key", ApiErrorType.DUPLICATE_VEHICLE_NUMBER, "Duplicate vehicle number",
            "vehicleNumber", "This vehicle number is already registered.");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(List.of(VEHICLE_NUMBER_RULE));
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/vehicles");

    @Test
    void br01_violationOfAKnownUniqueConstraintIsTheSameDuplicate409() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uk_vehicles_number_key\""));

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(violation, request);

        ProblemDetail problem = response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(ApiErrorType.DUPLICATE_VEHICLE_NUMBER);
        assertThat(problem.getInstance()).hasToString("/api/v1/vehicles");
        assertThat(problem.getProperties()).containsEntry(ProblemDetails.ERRORS_PROPERTY,
                List.of(new FieldErrorResponse("vehicleNumber", "This vehicle number is already registered.")));
    }

    @Test
    void otherIntegrityViolationsAreAGenericConflictWithoutDatabaseDetails() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("could not execute statement",
                new SQLException("violates check constraint \"ck_vehicles_status\""));

        ProblemDetail problem = handler.handleDataIntegrity(violation, request).getBody();

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(ApiErrorType.CONFLICT);
        assertThat(problem.getDetail()).doesNotContain("ck_vehicles_status");
        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void unexpectedExceptionsBecome500WithoutInternalDetails() {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("connection to db-host:5432 refused"), request).getBody();

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getType()).isEqualTo(ApiErrorType.INTERNAL);
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
    }

    @Test
    void databaseNotReachableIs503WithoutConnectionDetails() {
        ProblemDetail fromTransaction = handler.handleDatabaseUnavailable(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction: connection to db-host:5432 refused"), request)
                .getBody();
        ProblemDetail fromQuery = handler.handleDatabaseUnavailable(
                new DataAccessResourceFailureException("Unable to acquire JDBC Connection"), request).getBody();

        for (ProblemDetail problem : List.of(fromTransaction, fromQuery)) {
            assertThat(problem.getStatus()).isEqualTo(503);
            assertThat(problem.getType()).isEqualTo(ApiErrorType.INTERNAL);
            assertThat(problem.getTitle()).isEqualTo("Service unavailable");
            assertThat(problem.getDetail())
                    .isEqualTo("The database is not reachable right now. Please try again shortly.");
            assertThat(problem.getInstance()).hasToString("/api/v1/vehicles");
        }
    }

    @Test
    void adr0023_staleVersionIs409WithoutErrors() {
        ProblemDetail problem = handler.handleStaleVersion(new StaleVersionException("vehicle"), request).getBody();

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(ApiErrorType.STALE_VERSION);
        assertThat(problem.getTitle()).isEqualTo("Record changed");
        assertThat(problem.getDetail())
                .isEqualTo("This vehicle was changed by someone else since you opened it. Reload and try again.");
        assertThat(problem.getInstance()).hasToString("/api/v1/vehicles");
        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void adr0023_lostOptimisticLockRaceIsTheSame409NamingTheRecord() {
        ProblemDetail problem = handler.handleOptimisticLockingFailure(new ObjectOptimisticLockingFailureException(
                "com.starkgroup.vms.vehicle.Vehicle", 57L), request).getBody();

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(ApiErrorType.STALE_VERSION);
        assertThat(problem.getTitle()).isEqualTo("Record changed");
        assertThat(problem.getDetail())
                .isEqualTo("This vehicle was changed by someone else since you opened it. Reload and try again.");
        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void recordNounIsDerivedFromTheEntityName() {
        assertThat(GlobalExceptionHandler.recordNoun("com.starkgroup.vms.vehicle.Vehicle")).isEqualTo("vehicle");
        assertThat(GlobalExceptionHandler.recordNoun("MaintenanceRecord")).isEqualTo("maintenance record");
        assertThat(GlobalExceptionHandler.recordNoun(null)).isEqualTo("record");
        assertThat(GlobalExceptionHandler.recordNoun(" ")).isEqualTo("record");
    }

    @Test
    void adr0022_tooManyLoginAttemptsIs429WithRetryAfterRoundedUp() {
        ResponseEntity<ProblemDetail> response = handler.handleTooManyLoginAttempts(
                new TooManyLoginAttemptsException(Duration.ofSeconds(59, 1)), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(ApiErrorType.TOO_MANY_LOGIN_ATTEMPTS);
        assertThat(problem.getTitle()).isEqualTo("Too many login attempts");
        assertThat(problem.getDetail()).isEqualTo("Too many failed login attempts. Try again in 1 minute.");
        assertThat(problem.getInstance()).hasToString("/api/v1/vehicles");
        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void adr0022_retryAfterIsAtLeastOneSecondAndMinutesAreRoundedUp() {
        assertThat(new TooManyLoginAttemptsException(Duration.ofMillis(1)).getRetryAfterSeconds()).isEqualTo(1);
        assertThat(new TooManyLoginAttemptsException(Duration.ZERO).getRetryAfterSeconds()).isEqualTo(1);
        assertThat(new TooManyLoginAttemptsException(Duration.ofSeconds(840)).getMessage())
                .isEqualTo("Too many failed login attempts. Try again in 14 minutes.");
        assertThat(new TooManyLoginAttemptsException(Duration.ofSeconds(841)).getMessage())
                .isEqualTo("Too many failed login attempts. Try again in 15 minutes.");
    }
}
