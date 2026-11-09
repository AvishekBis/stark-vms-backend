package com.starkgroup.vms.common.web;

import java.net.URI;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.ProblemDetails;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Replaces Spring Boot's default /error JSON so that errors raised outside Spring MVC (for example
 * a URL rejected by the security firewall) are Problem Details too. Only a generic detail per
 * status is returned - never the exception message or a stack trace.
 */
@Hidden
@RestController
public class ProblemDetailsErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        ProblemDetail problem = ProblemDetails.of(status, ApiErrorType.forStatus(status), status.getReasonPhrase(),
                detailFor(status));
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        problem.setInstance(URI.create(originalUri instanceof String uri ? uri : request.getRequestURI()));
        return ResponseEntity.status(status).body(problem);
    }

    /** No status attribute means /error was requested directly: there is nothing to show. */
    private static HttpStatus resolveStatus(Object statusAttribute) {
        if (!(statusAttribute instanceof Integer code)) {
            return HttpStatus.NOT_FOUND;
        }
        HttpStatus status = HttpStatus.resolve(code);
        return status == null || status.value() < 400 ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    private static String detailFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request could not be processed.";
            case UNAUTHORIZED -> "Authentication is required to access this resource.";
            case FORBIDDEN -> "You do not have permission to perform this action.";
            case NOT_FOUND -> "The requested resource was not found.";
            default -> status.is5xxServerError()
                    ? "An unexpected error occurred."
                    : "The request could not be completed.";
        };
    }
}
