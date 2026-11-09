package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * FR-AUTH-01 / FR-AUTH-02: token issuing, verification and expiry.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-10T04:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, 60), clock);

    @Test
    void frAuth01_generatedTokenParsesBackToTheSameUser() {
        String token = jwtService.generateToken(5L, "admin@stark.local", Role.ADMIN);

        AuthenticatedUser user = jwtService.parse(token);

        assertThat(user.id()).isEqualTo(5L);
        assertThat(user.email()).isEqualTo("admin@stark.local");
        assertThat(user.role()).isEqualTo(Role.ADMIN);
        assertThat(user.authorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void frAuth02_expiresInIsReportedInSeconds() {
        assertThat(jwtService.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void frAuth02_tokenIsRejectedAfterExpiry() {
        String token = jwtService.generateToken(5L, "staff@stark.local", Role.STAFF);
        Clock later = Clock.fixed(NOW.plus(Duration.ofMinutes(61)), ZoneOffset.UTC);
        JwtService laterService = new JwtService(new JwtProperties(SECRET, 60), later);

        assertThatThrownBy(() -> laterService.parse(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("The access token has expired.");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService otherService = new JwtService(
                new JwtProperties("another-secret-that-is-long-enough-0123456789abcdef", 60), clock);
        String foreignToken = otherService.generateToken(5L, "admin@stark.local", Role.ADMIN);

        assertThatThrownBy(() -> jwtService.parse(foreignToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("The access token is invalid.");
    }

    @Test
    void garbageTokenIsRejected() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> jwtService.parse("")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void startupFailsWhenSecretIsShorterThan256Bits() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtService(new JwtProperties("too-short", 60), clock))
                .withMessageContaining("256 bits");
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtService(new JwtProperties(null, 60), clock));
    }

    @Test
    void startupFailsWhenExpiryIsNotPositive() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtService(new JwtProperties(SECRET, 0), clock));
    }
}
