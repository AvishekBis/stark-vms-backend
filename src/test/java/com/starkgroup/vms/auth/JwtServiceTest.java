package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * FR-AUTH-01 / FR-AUTH-02: token issuing, verification and expiry.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-10T04:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, 60), clock);

    @Test
    void frAuth01_generatedTokenParsesBackToTheSameUserAndIssueTime() {
        String token = jwtService.generateToken(5L, "admin@stark.local", Role.ADMIN);

        TokenClaims claims = jwtService.parse(token);

        assertThat(claims.userId()).isEqualTo(5L);
        assertThat(claims.email()).isEqualTo("admin@stark.local");
        assertThat(claims.issuedAt()).isEqualTo(NOW);
    }

    @Test
    void principalAuthorityIsBuiltFromTheRole() {
        AuthenticatedUser user = new AuthenticatedUser(5L, "admin@stark.local", Role.ADMIN);

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

    /** Review finding SEC-3: the issue time keeps its milliseconds (claim iat_ms), not only whole seconds. */
    @Test
    void issueTimeIsKeptToTheMillisecond() {
        Instant precise = NOW.plusNanos(734_567_891);
        JwtService preciseService = new JwtService(new JwtProperties(SECRET, 60), Clock.fixed(precise,
                ZoneOffset.UTC));

        TokenClaims claims = preciseService.parse(preciseService.generateToken(5L, "admin@stark.local", Role.ADMIN));

        assertThat(claims.issuedAt()).isEqualTo(NOW.plusMillis(734));
    }

    @Test
    void aTokenWithoutTheMillisecondClaimFallsBackToIat() {
        String legacyToken = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("admin@stark.local")
                .claim("uid", 5L)
                .claim("role", "ADMIN")
                .issuedAt(Date.from(NOW.plusMillis(900)))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.parse(legacyToken).issuedAt()).isEqualTo(NOW);
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
