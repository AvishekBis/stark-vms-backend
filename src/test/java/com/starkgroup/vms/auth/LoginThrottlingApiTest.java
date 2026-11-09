package com.starkgroup.vms.auth;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.starkgroup.vms.support.ApiTestSupport;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * ADR 0022 / contract 6.1 against the real filter chain: 429 + Retry-After + Problem Details, no password check
 * while locked, no account enumeration, per-IP limit (X-Forwarded-For handling needs a real Tomcat: see
 * {@link ClientIpApiTest}). Every test uses its own e-mails
 * and client IPs, because the in-memory counters live as long as the shared Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class LoginThrottlingApiTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String TOO_MANY = "https://stark-vms/errors/too-many-login-attempts";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(10);

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    LoginThrottlingApiTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void removeThrottleUsers() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'throttle-%'");
    }

    @Test
    void sixthAttemptAfterFiveFailuresIs429WithRetryAfterAndProblemDetails() throws Exception {
        String email = createUser();
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password", ip).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid email or password."));
        }

        MvcResult result = login(email, "wrong-password", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TOO_MANY))
                .andExpect(jsonPath("$.title").value("Too many login attempts"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Too many failed login attempts. Try again in 15 minutes."))
                .andExpect(jsonPath("$.instance").value(LOGIN))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();
        long retryAfter = Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(840L, 900L);
    }

    @Test
    void correctPasswordWhileLockedIsAlso429AndALockOnTheEmailAppliesFromEveryIp() throws Exception {
        String email = createUser();
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        }

        login(email, ApiTestSupport.STAFF_PASSWORD, ip).andExpect(status().isTooManyRequests());
        login(" " + email.toUpperCase(Locale.ROOT) + " ", ApiTestSupport.STAFF_PASSWORD, nextIp())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(TOO_MANY));
    }

    @Test
    void unknownEmailIsLockedExactlyLikeAnExistingOne() throws Exception {
        String unknown = "throttle-nobody-" + UUID.randomUUID() + "@stark.local";
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            login(unknown, "wrong-password", ip).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid email or password."));
        }

        login(unknown, "wrong-password", ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Too many failed login attempts. Try again in 15 minutes."))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void successfulLoginClearsTheEmailCount() throws Exception {
        String email = createUser();
        String ip = nextIp();
        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        login(email, ApiTestSupport.STAFF_PASSWORD, ip).andExpect(status().isOk());

        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        login(email, ApiTestSupport.STAFF_PASSWORD, ip).andExpect(status().isOk());
    }

    @Test
    void twentyFailuresFromOneIpBlockThatIpForEveryEmail() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 20; i++) {
            login("throttle-ip-" + i + "-" + UUID.randomUUID() + "@stark.local", "wrong-password", ip)
                    .andExpect(status().isUnauthorized());
        }
        String email = createUser();

        login(email, ApiTestSupport.STAFF_PASSWORD, ip).andExpect(status().isTooManyRequests());
        login(email, ApiTestSupport.STAFF_PASSWORD, nextIp()).andExpect(status().isOk());
    }

    @Test
    void blankFieldsAreNotCountedAsFailures() throws Exception {
        String email = createUser();
        String ip = nextIp();
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post(LOGIN).with(request -> {
                        request.setRemoteAddr(ip);
                        return request;
                    }).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        login(email, ApiTestSupport.STAFF_PASSWORD, ip).andExpect(status().isOk());
    }

    @Test
    void theBrowserMayReadRetryAfter() throws Exception {
        String unknown = "throttle-cors-" + UUID.randomUUID() + "@stark.local";
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            login(unknown, "wrong-password", ip).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post(LOGIN).with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }).header(HttpHeaders.ORIGIN, "http://localhost:5000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiTestSupport.loginBody(unknown, "wrong-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(HttpHeaders.RETRY_AFTER)));
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post(LOGIN).with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(ApiTestSupport.loginBody(email, password)));
    }

    /** A STAFF account with the staff demo password (copied hash), so the shared demo accounts are never locked. */
    private String createUser() {
        String email = "throttle-" + UUID.randomUUID() + "@stark.local";
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class,
                ApiTestSupport.STAFF_EMAIL);
        jdbcTemplate.update("INSERT INTO users (email, password_hash, full_name, role) VALUES (?, ?, ?, 'STAFF')",
                email, hash, "Throttle Test");
        return email;
    }

    private static String nextIp() {
        int n = NEXT_IP.getAndIncrement();
        return "198.51." + (n / 250) + "." + (n % 250 + 1);
    }
}
