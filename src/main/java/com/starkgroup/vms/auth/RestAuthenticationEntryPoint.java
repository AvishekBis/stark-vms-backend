package com.starkgroup.vms.auth;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.web.ProblemResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401 Problem Details for a missing, invalid or expired token (TC-06, FR-AUTH-02).
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String MISSING_TOKEN_DETAIL = "Authentication is required to access this resource.";

    private final ProblemResponseWriter problemResponseWriter;

    public RestAuthenticationEntryPoint(ProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object tokenError = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        String detail = tokenError instanceof String message ? message : MISSING_TOKEN_DETAIL;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problemResponseWriter.write(request, response, HttpStatus.UNAUTHORIZED, ApiErrorType.UNAUTHORIZED,
                "Unauthorized", detail);
    }
}
