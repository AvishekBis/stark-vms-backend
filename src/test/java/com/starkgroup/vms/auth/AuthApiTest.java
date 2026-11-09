package com.starkgroup.vms.auth;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.starkgroup.vms.support.ApiTestSupport;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * FR-AUTH-01 / FR-AUTH-02 against the real security filter chain and database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class AuthApiTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String GENERIC_LOGIN_ERROR = "Invalid email or password.";

    private final MockMvc mockMvc;
    private final JwtProperties jwtProperties;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    AuthApiTest(MockMvc mockMvc, JwtProperties jwtProperties, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jwtProperties = jwtProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void frAuth01_validLoginReturnsBearerTokenAndUser() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiTestSupport.loginBody("  Admin@Stark.Local ", ApiTestSupport.ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.id").isNumber())
                .andExpect(jsonPath("$.user.email").value(ApiTestSupport.ADMIN_EMAIL))
                .andExpect(jsonPath("$.user.fullName").value("Fleet Admin"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void frAuth01_wrongPasswordReturns401WithGenericMessage() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiTestSupport.loginBody(ApiTestSupport.ADMIN_EMAIL, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/unauthorized"))
                .andExpect(jsonPath("$.detail").value(GENERIC_LOGIN_ERROR))
                .andExpect(jsonPath("$.instance").value(LOGIN));
    }

    @Test
    void frAuth01_unknownEmailReturnsTheSameGenericMessage() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiTestSupport.loginBody("nobody@stark.local", ApiTestSupport.ADMIN_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC_LOGIN_ERROR));
    }

    @Test
    void frAuth01_disabledUserCannotLogIn() throws Exception {
        String email = "disabled@stark.local";
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class,
                ApiTestSupport.STAFF_EMAIL);
        jdbcTemplate.update("INSERT INTO users (email, password_hash, full_name, role, enabled) "
                + "VALUES (?, ?, 'Disabled Officer', 'STAFF', FALSE)", email, hash);

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiTestSupport.loginBody(email, ApiTestSupport.STAFF_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC_LOGIN_ERROR));
    }

    @Test
    void frAuth01_blankFieldsReturn400WithFieldErrors() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/validation"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void frAuth02_meReturnsCurrentUserForEachRole() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, ApiTestSupport.staffToken(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ApiTestSupport.STAFF_EMAIL))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.fullName").value("Fleet Officer"));
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminToken(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void tc06_meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://stark-vms/errors/unauthorized"))
                .andExpect(jsonPath("$.detail").value("Authentication is required to access this resource."));
    }

    @Test
    void frAuth02_expiredTokenReturns401() throws Exception {
        Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String expiredToken = new JwtService(jwtProperties, twoHoursAgo)
                .generateToken(1L, ApiTestSupport.ADMIN_EMAIL, Role.ADMIN);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The access token has expired."));
    }

    @Test
    void garbageTokenReturns401() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The access token is invalid."));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles']").exists())
                // The real status codes are documented, not only the generated 200.
                .andExpect(jsonPath("$.paths['/api/v1/vehicles'].post.responses['201'].headers.Location").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles/{id}'].delete.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['401']").exists());
    }

    @Test
    void bootstrapCreatesConfiguredAccountsWithHashedPasswords() {
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class,
                ApiTestSupport.ADMIN_EMAIL);
        assertThat(hash).startsWith("$2").isNotEqualTo(ApiTestSupport.ADMIN_PASSWORD);
    }
}
