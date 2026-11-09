package com.starkgroup.vms.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(email)
                .claim(USER_ID_CLAIM, userId)
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(expiration)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public long expiresInSeconds() {
        return expiration.toSeconds();
    }

    /**
     * @throws InvalidTokenException when the token cannot be trusted
     */
    public AuthenticatedUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Number userId = claims.get(USER_ID_CLAIM, Number.class);
            String role = claims.get(ROLE_CLAIM, String.class);
            if (userId == null || role == null || claims.getSubject() == null) {
                throw new InvalidTokenException("The access token is invalid.", null);
            }
            return new AuthenticatedUser(userId.longValue(), claims.getSubject(), Role.valueOf(role));
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException("The access token has expired.", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("The access token is invalid.", ex);
        }
    }
}
