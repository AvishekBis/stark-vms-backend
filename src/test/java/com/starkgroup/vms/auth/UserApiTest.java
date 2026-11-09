package com.starkgroup.vms.auth;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starkgroup.vms.auth.dto.UpdateUserRequest;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.support.ApiTestSupport;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * ADR 0024 / contract 7.3 against the real filter chain and database: ADMIN-only endpoints, Problem Details, the
 * guards, and the token checks (disable, demote, reset, own change take effect on the next request). The bootstrap
 * accounts are never modified; every other account is removed before and after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class UserApiTest {

    private static final String USERS = "/api/v1/users";
    private static final String OWN_PASSWORD = "/api/v1/auth/me/password";
    private static final String PASSWORD = "Welcome@2026";

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final JwtProperties jwtProperties;
    private final UserService userService;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    private String adminToken;
    private String staffToken;

    @Autowired
    UserApiTest(MockMvc mockMvc, JdbcTemplate jdbcTemplate, JwtProperties jwtProperties, UserService userService,
                TransactionTemplate transactionTemplate, DataSource dataSource) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.jwtProperties = jwtProperties;
        this.userService = userService;
        this.transactionTemplate = transactionTemplate;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void setUp() throws Exception {
        removeTestUsers();
        adminToken = ApiTestSupport.adminToken(mockMvc);
        staffToken = ApiTestSupport.staffToken(mockMvc);
    }

    /**
     * Also puts the bootstrap accounts back (enabled, original role), so a failing test can never leave the shared
     * ADMIN disabled and make every later test of the shared context fail at login.
     */
    @AfterEach
    void removeTestUsers() {
        jdbcTemplate.update("DELETE FROM users WHERE email NOT IN (?, ?)", ApiTestSupport.ADMIN_EMAIL,
                ApiTestSupport.STAFF_EMAIL);
        jdbcTemplate.update("UPDATE users SET enabled = TRUE, role = 'ADMIN' WHERE email = ? "
                + "AND NOT (enabled AND role = 'ADMIN')", ApiTestSupport.ADMIN_EMAIL);
        jdbcTemplate.update("UPDATE users SET enabled = TRUE, role = 'STAFF' WHERE email = ? "
                + "AND NOT (enabled AND role = 'STAFF')", ApiTestSupport.STAFF_EMAIL);
    }

    @Test
    void staffGets403OnEveryUsersEndpoint() throws Exception {
        long adminId = idOf(ApiTestSupport.ADMIN_EMAIL);
        expectForbidden(mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, staffToken)));
        expectForbidden(mockMvc.perform(get(USERS + "/" + adminId).header(HttpHeaders.AUTHORIZATION, staffToken)));
        expectForbidden(mockMvc.perform(post(USERS).header(HttpHeaders.AUTHORIZATION, staffToken)
                .contentType(MediaType.APPLICATION_JSON).content(createJson("x@stark.local", "STAFF").toString())));
        expectForbidden(mockMvc.perform(put(USERS + "/" + adminId).header(HttpHeaders.AUTHORIZATION, staffToken)
                .contentType(MediaType.APPLICATION_JSON).content(updateJson("X", "STAFF", false, 0).toString())));
        expectForbidden(mockMvc.perform(post(USERS + "/" + adminId + "/password")
                .header(HttpHeaders.AUTHORIZATION, staffToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Temp@2026x\"}")));
        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns201WithLocationNormalizedEmailAndAuditFields() throws Exception {
        mockMvc.perform(post(USERS).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(" Officer2@Stark.LOCAL ", "STAFF").put("fullName", " Second Officer ")
                                .toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/users/\\d+$")))
                .andExpect(jsonPath("$.email").value("officer2@stark.local"))
                .andExpect(jsonPath("$.fullName").value("Second Officer"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdBy").value(ApiTestSupport.ADMIN_EMAIL))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.passwordChangedAt").doesNotExist());

        ApiTestSupport.bearerToken(mockMvc, "officer2@stark.local", PASSWORD);
    }

    @Test
    void duplicateEmailInAnotherSpellingIs409OnTheEmailField() throws Exception {
        createUser("officer2@stark.local", "STAFF");

        mockMvc.perform(post(USERS).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("  OFFICER2@stark.local", "ADMIN").toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/duplicate-email"))
                .andExpect(jsonPath("$.title").value("Duplicate e-mail"))
                .andExpect(jsonPath("$.detail").value("E-mail officer2@stark.local is already registered."))
                .andExpect(jsonPath("$.instance").value(USERS))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void createValidatesEmailRoleAndThePasswordPolicy() throws Exception {
        ObjectNode body = createJson("not-an-email", "STAFF").put("password", "Short@1").put("fullName", " ");
        body.remove("role");
        mockMvc.perform(post(USERS).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors[?(@.field=='email')].message")
                        .value("E-mail must be a valid e-mail address."))
                .andExpect(jsonPath("$.errors[?(@.field=='password')].message")
                        .value("Password must be at least 8 characters."))
                .andExpect(jsonPath("$.errors[?(@.field=='role')].message").value("Role is required."))
                .andExpect(jsonPath("$.errors[?(@.field=='fullName')].message").value("Full name is required."));

        mockMvc.perform(post(USERS).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("long@stark.local", "STAFF").put("password", "a".repeat(73)).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Password must be at most 72 bytes (UTF-8)."));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
    }

    @Test
    void listSearchesFiltersAndSorts() throws Exception {
        createUser("zeta.officer@stark.local", "STAFF");
        long disabled = createUser("alpha.officer@stark.local", "STAFF").get("id").asLong();
        createUser("second.admin@stark.local", "ADMIN");
        jdbcTemplate.update("UPDATE users SET enabled = FALSE WHERE id = ?", disabled);

        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].email").value("admin@stark.local"))
                .andExpect(jsonPath("$.content[0].createdBy").value("system"))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.content[1].email").value("alpha.officer@stark.local"));
        // e-mail or full name: zeta/alpha by e-mail, staff ("Fleet Officer") and second.admin ("Test Officer") by name
        mockMvc.perform(get(USERS).param("search", "OFFICER").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(get(USERS).param("search", "officer").param("enabled", "true").param("role", "STAFF")
                        .param("sort", "email,desc").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].email").value("zeta.officer@stark.local"))
                .andExpect(jsonPath("$.content[1].email").value("staff@stark.local"));
        mockMvc.perform(get(USERS).param("enabled", "false").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].enabled").value(false));
        mockMvc.perform(get(USERS).param("role", "ADMIN").param("sort", "fullName,asc").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listRejectsBadSortRoleAndEnabledValues() throws Exception {
        mockMvc.perform(get(USERS).param("sort", "passwordHash,asc").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(USERS).param("role", "ROOT").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(USERS).param("enabled", "maybe").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOneAndMissingUser() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();

        mockMvc.perform(get(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("officer2@stark.local"));
        mockMvc.perform(get(USERS + "/999999").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/not-found"));
    }

    @Test
    void updateBumpsTheVersionIgnoresTheEmailAndASecondSaveWithTheOldVersionIsStale() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();
        ObjectNode change = updateJson("Renamed Officer", "STAFF", true, 0).put("email", "hacker@stark.local");

        mockMvc.perform(put(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(change.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Renamed Officer"))
                .andExpect(jsonPath("$.email").value("officer2@stark.local"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("Other Name", "ADMIN", true, 0).toString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/stale-version"))
                .andExpect(jsonPath("$.title").value("Record changed"))
                .andExpect(jsonPath("$.detail").value(
                        "This user was changed by someone else since you opened it. Reload and try again."))
                .andExpect(jsonPath("$.instance").value(USERS + "/" + id))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, id))
                .isEqualTo("STAFF");
    }

    @Test
    void updateRequiresAllFieldsIncludingVersion() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();

        mockMvc.perform(put(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='version')].message").value("Version is required."))
                .andExpect(jsonPath("$.errors[?(@.field=='role')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='enabled')]").exists());
        mockMvc.perform(put(USERS + "/999999").header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(updateJson("X", "STAFF", true, 0).toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCannotDisableThemselfOrRemoveTheirOwnAdminRole() throws Exception {
        long adminId = idOf(ApiTestSupport.ADMIN_EMAIL);
        long version = versionOf(adminId);

        for (ObjectNode change : new ObjectNode[] {updateJson("Fleet Admin", "ADMIN", false, version),
                updateJson("Fleet Admin", "STAFF", true, version)}) {
            mockMvc.perform(put(USERS + "/" + adminId).header(HttpHeaders.AUTHORIZATION, adminToken)
                            .contentType(MediaType.APPLICATION_JSON).content(change.toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://stark-vms/errors/self-modification"))
                    .andExpect(jsonPath("$.title").value("Not allowed on your own account"))
                    .andExpect(jsonPath("$.detail")
                            .value("You cannot disable your own account or remove your own ADMIN role."))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, adminId))
                .isTrue();
    }

    @Test
    void disablingAUserRejectsTheirTokenOnTheNextRequestAndLoginStaysTheGeneric401() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();
        String officerToken = ApiTestSupport.bearerToken(mockMvc, "officer2@stark.local", PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, officerToken))
                .andExpect(status().isOk());

        mockMvc.perform(put(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("Second Officer", "STAFF", false, 0).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/v1/vehicles").header(HttpHeaders.AUTHORIZATION, officerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/unauthorized"))
                .andExpect(jsonPath("$.detail").value("The access token is no longer valid. Please log in again."));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        })
                        .content(ApiTestSupport.loginBody("officer2@stark.local", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));
    }

    /** Review finding SEC-5: enabling the account again does not bring back tokens issued before the disable. */
    @Test
    void reEnablingAUserDoesNotRestoreTokensIssuedBeforeTheDisable() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();
        String oldToken = tokenIssuedSecondsAgo(id, "officer2@stark.local", Role.STAFF, 5);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, oldToken))
                .andExpect(status().isOk());

        mockMvc.perform(put(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("Test Officer", "STAFF", false, versionOf(id)).toString()))
                .andExpect(status().isOk());
        mockMvc.perform(put(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("Test Officer", "STAFF", true, versionOf(id)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The access token is no longer valid. Please log in again."));
        String newToken = ApiTestSupport.bearerToken(mockMvc, "officer2@stark.local", PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, newToken))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT tokens_revoked_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    /**
     * Review finding SEC-7 / F3: wrong current passwords count as failed logins of the e-mail; after 5 the change
     * is refused with 429 (not 401, so the client keeps the session) and login is locked too. A unique e-mail,
     * because the in-memory lock outlives the test in the shared context.
     */
    @Test
    void wrongCurrentPasswordsAreThrottledLikeFailedLogins() throws Exception {
        String email = "throttled-" + UUID.randomUUID() + "@stark.local";
        createUser(email, "STAFF");
        String token = ApiTestSupport.bearerToken(mockMvc, email, PASSWORD);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(put(OWN_PASSWORD).header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"Wrong@1234\",\"newPassword\":\"N3w-Staff-Pass\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));
        }

        mockMvc.perform(put(OWN_PASSWORD).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"N3w-Staff-Pass\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/too-many-login-attempts"))
                .andExpect(jsonPath("$.detail").value("Too many failed login attempts. Try again in 15 minutes."));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.11");
                            return request;
                        })
                        .content(ApiTestSupport.loginBody(email, PASSWORD)))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbcTemplate.queryForObject("SELECT password_changed_at IS NULL FROM users WHERE email = ?",
                Boolean.class, email)).isTrue();
    }

    @Test
    void theEffectiveRoleIsTheCurrentDatabaseRole() throws Exception {
        long id = createUser("second.admin@stark.local", "ADMIN").get("id").asLong();
        String secondAdminToken = ApiTestSupport.bearerToken(mockMvc, "second.admin@stark.local", PASSWORD);
        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, secondAdminToken)).andExpect(status().isOk());

        mockMvc.perform(put(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("Second Admin", "STAFF", true, 0).toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, secondAdminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/vehicles").header(HttpHeaders.AUTHORIZATION, secondAdminToken))
                .andExpect(status().isOk());
        // A token whose role claim says ADMIN does not make a STAFF user an ADMIN.
        String forgedRoleClaim = tokenIssuedSecondsAgo(idOf(ApiTestSupport.STAFF_EMAIL), ApiTestSupport.STAFF_EMAIL,
                Role.ADMIN, 0);
        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, forgedRoleClaim))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenOfADeletedUserIs401() throws Exception {
        createUser("officer2@stark.local", "STAFF");
        String officerToken = ApiTestSupport.bearerToken(mockMvc, "officer2@stark.local", PASSWORD);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", "officer2@stark.local");

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, officerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminResetRevokesOlderTokensBumpsTheVersionAndTheNewPasswordWorks() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();
        String olderToken = tokenIssuedSecondsAgo(id, "officer2@stark.local", Role.STAFF, 5);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, olderToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(USERS + "/" + id + "/password").header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Temp@2026x\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, olderToken))
                .andExpect(status().isUnauthorized());
        String newToken = ApiTestSupport.bearerToken(mockMvc, "officer2@stark.local", "Temp@2026x");
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, newToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT password_changed_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void adminResetChecksThePolicyAndTheId() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();

        mockMvc.perform(post(USERS + "/" + id + "/password").header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        mockMvc.perform(post(USERS + "/999999/password").header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Temp@2026x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownPasswordChangeWorksForStaffAndEndsTheOldSession() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();
        String olderToken = tokenIssuedSecondsAgo(id, "officer2@stark.local", Role.STAFF, 5);

        mockMvc.perform(put(OWN_PASSWORD).header(HttpHeaders.AUTHORIZATION, olderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"N3w-Staff-Pass\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, olderToken))
                .andExpect(status().isUnauthorized());
        String reLogin = ApiTestSupport.bearerToken(mockMvc, "officer2@stark.local", "N3w-Staff-Pass");
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, reLogin))
                .andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordIs400OnThatFieldAndKeepsTheSession() throws Exception {
        createUser("officer2@stark.local", "STAFF");
        String token = ApiTestSupport.bearerToken(mockMvc, "officer2@stark.local", PASSWORD);

        mockMvc.perform(put(OWN_PASSWORD).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Wrong@1234\",\"newPassword\":\"N3w-Staff-Pass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Current password is incorrect."))
                .andExpect(jsonPath("$.instance").value(OWN_PASSWORD))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.errors[0].message").value("Current password is incorrect."));
        mockMvc.perform(put(OWN_PASSWORD).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(put(OWN_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"N3w-Staff-Pass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thereIsNoDeleteEndpoint() throws Exception {
        long id = createUser("officer2@stark.local", "STAFF").get("id").asLong();

        mockMvc.perform(delete(USERS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    /**
     * Two ADMINs disable each other at the same moment. The first transaction (bootstrap ADMIN disables B) holds the
     * row locks of the enabled ADMINs; the second (B disables the bootstrap ADMIN) must wait for it, then see only one
     * enabled ADMIN and get last-admin. Without the lock both would commit and no enabled ADMIN would remain.
     */
    @Test
    void twoAdminsDisablingEachOtherAtOnceLeaveOneEnabledAdmin() throws Exception {
        long adminId = idOf(ApiTestSupport.ADMIN_EMAIL);
        long secondId = createUser("second.admin@stark.local", "ADMIN").get("id").asLong();
        long adminVersion = versionOf(adminId);
        AuthenticatedUser admin = new AuthenticatedUser(adminId, ApiTestSupport.ADMIN_EMAIL, Role.ADMIN);
        AuthenticatedUser second = new AuthenticatedUser(secondId, "second.admin@stark.local", Role.ADMIN);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> concurrent = transactionTemplate.execute(status -> {
                userService.update(admin, secondId, new UpdateUserRequest("Second Admin", Role.ADMIN, false, 0L));
                Future<?> pending = executor.submit(() -> userService.update(second, adminId,
                        new UpdateUserRequest("Fleet Admin", Role.ADMIN, false, adminVersion)));
                awaitABackendWaitingForALock();
                return pending;
            });

            assertThatThrownBy(() -> concurrent.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(BusinessRuleException.class,
                            ex -> assertThat(ex.getType()).isEqualTo(ApiErrorType.LAST_ADMIN));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, adminId))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN' AND enabled",
                Integer.class)).isEqualTo(1);
    }

    /**
     * Polls on its own auto-commit connection: inside the caller's transaction PostgreSQL would answer every poll from
     * the pg_stat_activity snapshot taken at the first one, and never see the other backend start waiting.
     */
    private void awaitABackendWaitingForALock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (Connection monitor = dataSource.getConnection();
             PreparedStatement waitingBackends = monitor.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                     + "WHERE wait_event_type = 'Lock' AND datname = current_database()")) {
            monitor.setAutoCommit(true);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = waitingBackends.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
        throw new AssertionError("The second change never waited for the ADMIN row locks.");
    }

    private ObjectNode createJson(String email, String role) {
        return json.createObjectNode().put("email", email).put("fullName", "Test Officer").put("role", role)
                .put("password", PASSWORD);
    }

    private ObjectNode updateJson(String fullName, String role, boolean enabled, long version) {
        return json.createObjectNode().put("fullName", fullName).put("role", role).put("enabled", enabled)
                .put("version", version);
    }

    private JsonNode createUser(String email, String role) throws Exception {
        return ApiTestSupport.readJson(mockMvc.perform(post(USERS).header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(email, role).toString()))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private long idOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long versionOf(long id) {
        return jdbcTemplate.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id);
    }

    /** A valid token issued some seconds ago, so a later password change or disable is clearly "after" it. */
    private String tokenIssuedSecondsAgo(long userId, String email, Role role, long seconds) {
        Clock past = Clock.fixed(Instant.now().minusSeconds(seconds), ZoneOffset.UTC);
        return "Bearer " + new JwtService(jwtProperties, past).generateToken(userId, email, role);
    }

    private static void expectForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/forbidden"));
    }
}
