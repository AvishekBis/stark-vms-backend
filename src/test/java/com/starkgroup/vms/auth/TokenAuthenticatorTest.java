package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ADR 0024 token checks: current database state wins over the token; cut-offs compared in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class TokenAuthenticatorTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef-0123456789";
    private static final Instant ISSUED = Instant.parse("2026-11-07T04:00:10Z");

    @Mock
    private UserRepository userRepository;

    private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, 60),
            Clock.fixed(ISSUED, ZoneOffset.UTC));

    @Test
    void roleComesFromTheDatabaseNotFromTheToken() {
        String token = jwtService.generateToken(7L, "officer@stark.local", Role.ADMIN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(Role.STAFF, true, null)));

        AuthenticatedUser principal = authenticator().authenticate(token);

        assertThat(principal.role()).isEqualTo(Role.STAFF);
        assertThat(principal.authorities()).extracting(Object::toString).containsExactly("ROLE_STAFF");
    }

    @Test
    void tokenOfADeletedOrDisabledUserIsRejected() {
        String token = jwtService.generateToken(7L, "officer@stark.local", Role.STAFF);
        when(userRepository.findById(7L)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(user(Role.STAFF, false, null)));

        assertThatThrownBy(() -> authenticator().authenticate(token)).isInstanceOf(InvalidTokenException.class)
                .hasMessage("The access token is no longer valid. Please log in again.");
        assertThatThrownBy(() -> authenticator().authenticate(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenIssuedBeforeThePasswordChangeIsRejected() {
        String token = jwtService.generateToken(7L, "officer@stark.local", Role.STAFF);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(Role.STAFF, true, ISSUED.plusSeconds(1))));

        assertThatThrownBy(() -> authenticator().authenticate(token)).isInstanceOf(InvalidTokenException.class);
    }

    /** Review finding SEC-3: a token from earlier in the same second as the change is rejected too. */
    @Test
    void tokenIssuedEarlierInTheSameSecondAsTheChangeIsRejected() {
        String token = tokenAt(ISSUED.plusMillis(400));
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(user(Role.STAFF, true, ISSUED.plusMillis(900))));

        assertThatThrownBy(() -> authenticator().authenticate(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenIssuedAfterTheChangeStaysValid() {
        String token = tokenAt(ISSUED.plusMillis(950));
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(user(Role.STAFF, true, ISSUED.plusMillis(900))));

        assertThat(authenticator().authenticate(token).id()).isEqualTo(7L);
    }

    /** Review finding SEC-5: a token from before a disable stays dead after the account is enabled again. */
    @Test
    void tokenIssuedBeforeADisableIsRejectedAfterReEnabling() {
        String token = jwtService.generateToken(7L, "officer@stark.local", Role.STAFF);
        User reEnabled = user(Role.STAFF, true, null);
        reEnabled.updateAccount("Officer", Role.STAFF, false, ISSUED.plusSeconds(60));
        reEnabled.updateAccount("Officer", Role.STAFF, true, ISSUED.plusSeconds(120));
        when(userRepository.findById(7L)).thenReturn(Optional.of(reEnabled));

        assertThat(reEnabled.getTokensRevokedAt()).isEqualTo(ISSUED.plusSeconds(60));
        assertThatThrownBy(() -> authenticator().authenticate(token)).isInstanceOf(InvalidTokenException.class);
        assertThat(authenticator().authenticate(tokenAt(ISSUED.plusSeconds(180))).id())
                .as("a login after re-enabling works").isEqualTo(7L);
    }

    @Test
    void comparisonIsInMilliseconds() {
        assertThat(TokenAuthenticator.issuedBefore(ISSUED, null)).isFalse();
        assertThat(TokenAuthenticator.issuedBefore(ISSUED, ISSUED.plusNanos(999_999))).as("same millisecond")
                .isFalse();
        assertThat(TokenAuthenticator.issuedBefore(ISSUED, ISSUED.plusMillis(1))).isTrue();
        assertThat(TokenAuthenticator.issuedBefore(ISSUED, ISSUED.minusMillis(1))).isFalse();
    }

    private String tokenAt(Instant issuedAt) {
        return new JwtService(new JwtProperties(SECRET, 60), Clock.fixed(issuedAt, ZoneOffset.UTC))
                .generateToken(7L, "officer@stark.local", Role.STAFF);
    }

    private TokenAuthenticator authenticator() {
        return new TokenAuthenticator(jwtService, userRepository);
    }

    private static User user(Role role, boolean enabled, Instant passwordChangedAt) {
        User user = new User("officer@stark.local", "hash", "Officer", role);
        user.updateAccount("Officer", role, enabled);
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "passwordChangedAt", passwordChangedAt);
        return user;
    }
}
