package com.starkgroup.vms.common.exception;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Factory for RFC 9457 Problem Details bodies, so every error has the same shape.
 */
public final class ProblemDetails {

    public static final String ERRORS_PROPERTY = "errors";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatusCode status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail withErrors(HttpStatusCode status, URI type, String title, String detail,
                                           List<FieldErrorResponse> errors) {
        ProblemDetail problem = of(status, type, title, detail);
        problem.setProperty(ERRORS_PROPERTY, errors);
        return problem;
    }
}
