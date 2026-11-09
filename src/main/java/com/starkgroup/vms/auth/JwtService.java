package com.starkgroup.vms.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies HS256 access tokens. Fails fast at start-up when the secret is shorter
 * than 256 bits (guideline section 13).
 */
@Service
public class JwtService {

    static final int MIN_SECRET_BYTES = 32;
    static final String ISSUER = "stark-vms";
    private static final String USER_ID_CLAIM = "uid";
    private static final String ROLE_CLAIM = "role";
    /**
     * Issue time in epoch milliseconds. JWT {@code iat} has whole seconds only, which let a token issued earlier
     * in the same second as a password change survive it (review finding SEC-3, ADR 0024).
     */
    static final String ISSUED_AT_MILLIS_CLAIM = "iat_ms";

    private final SecretKey signingKey;
    private final Duration expiration;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        byte[] secretBytes = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 256 bits (32 bytes) long.");
        }
        if (properties.expirationMinutes() <= 0) {
            throw new IllegalStateException("JWT_EXPIRATION_MINUTES must be greater than 0.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expiration = Duration.ofMinutes(properties.expirationMinutes());
        this.clock = clock;
    }

    public String generateToken(Long userId, String email, Role role) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(email)
                .claim(USER_ID_CLAIM, userId)
                .claim(ROLE_CLAIM, role.name())
                .claim(ISSUED_AT_MILLIS_CLAIM, issuedAt.toEpochMilli())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(expiration)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public long expiresInSeconds() {
        return expiration.toSeconds();
    }

    /**
     * Verifies signature, issuer and expiry. The role claim stays in the token for the client's information only:
     * the effective role is read from the database on every request (ADR 0024, {@link TokenAuthenticator}).
     *
     * @throws InvalidTokenException when the token cannot be trusted
     */
    public TokenClaims parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Number userId = claims.get(USER_ID_CLAIM, Number.class);
            Date issuedAt = claims.getIssuedAt();
            if (userId == null || issuedAt == null || claims.getSubject() == null) {
                throw new InvalidTokenException("The access token is invalid.", null);
            }
            Number issuedAtMillis = claims.get(ISSUED_AT_MILLIS_CLAIM, Number.class);
            Instant preciseIssuedAt = issuedAtMillis == null
                    ? issuedAt.toInstant()
                    : Instant.ofEpochMilli(issuedAtMillis.longValue());
            return new TokenClaims(userId.longValue(), claims.getSubject(), preciseIssuedAt);
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException("The access token has expired.", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("The access token is invalid.", ex);
        }
    }
}
