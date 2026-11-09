package com.starkgroup.vms.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

/**
 * Turns a bearer token into the request's principal (ADR 0024). After the signature and expiry check, the user is
 * loaded by primary key on every request, and the token is rejected when the user no longer exists, is disabled,
 * changed their password (or had it reset) after the token was issued, or was disabled after the token was issued
 * (also when re-enabled since, review finding SEC-5). The authority is the user's
 * <b>current</b> database role, not the token's role claim, so disabling, demoting or a password reset takes
 * effect on the next request without a token blacklist.
 */
@Service
public class TokenAuthenticator {

    static final String TOKEN_REVOKED = "The access token is no longer valid. Please log in again.";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public TokenAuthenticator(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * @throws InvalidTokenException when the token cannot be trusted or no longer belongs to an active account
     */
    public AuthenticatedUser authenticate(String token) {
        TokenClaims claims = jwtService.parse(token);
        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new InvalidTokenException(TOKEN_REVOKED, null));
        if (!user.isEnabled() || issuedBefore(claims.issuedAt(), user.getPasswordChangedAt())
                || issuedBefore(claims.issuedAt(), user.getTokensRevokedAt())) {
            throw new InvalidTokenException(TOKEN_REVOKED, null);
        }
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
    }

    /**
     * Compares in milliseconds (the precision of the token's {@code iat_ms} claim): only a token issued in the very
     * same millisecond as the change survives it. The client's automatic re-login after its own password change
     * happens a full request (and a BCrypt check) later, so it is never affected. Before review finding SEC-3 the
     * comparison was in whole seconds, which kept every token of that second valid.
     *
     * @param cutoff password change or token revocation time, {@code null} = none
     */
    static boolean issuedBefore(Instant issuedAt, Instant cutoff) {
        return cutoff != null && issuedAt.isBefore(cutoff.truncatedTo(ChronoUnit.MILLIS));
    }
}
