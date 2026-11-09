package com.starkgroup.vms.auth;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.web.ProblemResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 403 Problem Details when an authenticated user lacks the required role (TC-07).
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemResponseWriter;

    public RestAccessDeniedHandler(ProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        problemResponseWriter.write(request, response, HttpStatus.FORBIDDEN, ApiErrorType.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action.");
    }
}
