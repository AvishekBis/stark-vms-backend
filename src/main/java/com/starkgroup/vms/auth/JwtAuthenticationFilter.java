package com.starkgroup.vms.auth;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.web.ProblemResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads "Authorization: Bearer &lt;token&gt;" and, when valid, authenticates the request with the user's current
 * database state ({@link TokenAuthenticator}). An invalid token leaves the request anonymous; protected endpoints
 * then answer 401 via {@link RestAuthenticationEntryPoint}, which reports the reason stored here. When the database
 * cannot be reached for the user lookup the request ends with the same 503 Problem Details as any other database
 * outage (never a 401, which would log the client out).
 * Not a Spring bean on purpose: it must only run inside the security filter chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final TokenAuthenticator tokenAuthenticator;
    private final ProblemResponseWriter problemResponseWriter;

    public JwtAuthenticationFilter(TokenAuthenticator tokenAuthenticator, ProblemResponseWriter problemResponseWriter) {
        this.tokenAuthenticator = tokenAuthenticator;
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                AuthenticatedUser user = tokenAuthenticator.authenticate(token);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
                SecurityContextHolder.setContext(context);
            } catch (InvalidTokenException ex) {
                SecurityContextHolder.clearContext();
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ex.getMessage());
            } catch (DataAccessException | TransactionException ex) {
                log.warn("Database unavailable while checking the token on {} {}: {}", request.getMethod(),
                        request.getRequestURI(), ex.getMessage());
                SecurityContextHolder.clearContext();
                problemResponseWriter.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, ApiErrorType.INTERNAL,
                        "Service unavailable", "The database is not reachable right now. Please try again shortly.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
