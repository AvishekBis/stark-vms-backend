package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.starkgroup.vms.common.web.ProblemResponseWriter;

/**
 * ADR 0024 / contract 7.3.5 at the filter: the principal and its authority come from {@link TokenAuthenticator}
 * (current database state), a revoked token leaves the request anonymous with the reason for the 401, and a database
 * outage during the user lookup is a 503 - never a 401, which would log the client out.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "header.payload.signature";

    @Mock
    private TokenAuthenticator tokenAuthenticator;

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/vehicles");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesWithTheRoleTheAuthenticatorReturns() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        when(tokenAuthenticator.authenticate(TOKEN))
                .thenReturn(new AuthenticatedUser(7L, "officer@stark.local", Role.STAFF));

        filter().doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_STAFF");
        assertThat(chain.getRequest()).as("the request continues").isNotNull();
    }

    @Test
    void revokedTokenLeavesTheRequestAnonymousWithTheReasonForThe401() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        when(tokenAuthenticator.authenticate(TOKEN))
                .thenThrow(new InvalidTokenException(TokenAuthenticator.TOKEN_REVOKED, null));

        filter().doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(TokenAuthenticator.TOKEN_REVOKED);
        assertThat(chain.getRequest()).as("the entry point answers 401 later").isNotNull();
    }

    @Test
    void databaseOutageDuringTheUserLookupIs503AndNot401() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        when(tokenAuthenticator.authenticate(TOKEN))
                .thenThrow(new DataAccessResourceFailureException("Connection refused: db.internal:5432"));

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("\"type\":\"https://stark-vms/errors/internal\"")
                .contains("\"title\":\"Service unavailable\"")
                .contains("\"instance\":\"/api/v1/vehicles\"")
                .doesNotContain("db.internal");
        assertThat(chain.getRequest()).as("the request stops here").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void requestWithoutBearerTokenIsNotLookedUp() throws Exception {
        filter().doFilter(request, response, chain);

        verifyNoInteractions(tokenAuthenticator);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(tokenAuthenticator,
                new ProblemResponseWriter(Jackson2ObjectMapperBuilder.json().build()));
    }
}
