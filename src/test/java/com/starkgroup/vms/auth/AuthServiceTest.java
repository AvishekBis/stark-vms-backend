package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.starkgroup.vms.auth.dto.LoginRequest;
import com.starkgroup.vms.common.exception.InvalidCredentialsException;
import com.starkgroup.vms.common.exception.TooManyLoginAttemptsException;
import com.starkgroup.vms.support.MutableClock;

/**
 * ADR 0022 order inside login: a locked e-mail is refused before the password is checked, every generic 401 is
 * counted (reserved before the password check, so parallel requests cannot overshoot), a success clears the
 * e-mail's count, a database failure is not counted.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T04:00:00Z");
    private static final String IP = "203.0.113.7";
    private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef-0123456789";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private final MutableClock clock = new MutableClock(NOW, ZoneId.of("Asia/Dhaka"));
    private LoginAttemptService loginAttemptService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        loginAttemptService = new LoginAttemptService(new LoginThrottleProperties(5, 20, Duration.ofMinutes(15)),
                clock);
        authService = new AuthService(userRepository, passwordEncoder, new JwtService(new JwtProperties(SECRET, 60),
                clock), loginAttemptService);
    }

    @Test
    void lockedEmailIsRefusedWithoutCheckingThePassword() {
        for (int i = 0; i < 5; i++) {
            loginAttemptService.failed(loginAttemptService.tryAcquire("officer@stark.local", "198.51.100.1"));
        }

        assertThatThrownBy(() -> authService.login(new LoginRequest(" Officer@Stark.local ", "Correct@123"), IP))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class, ex -> {
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(900);
                    assertThat(ex.getMessage()).isEqualTo("Too many failed login attempts. Try again in 15 minutes.");
                });
        verify(userRepository, never()).findByEmail(anyString());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledUserAllCountAsFailures() {
        User disabled = user("disabled@stark.local");
        ReflectionTestUtils.setField(disabled, "enabled", false);
        when(userRepository.findByEmail("disabled@stark.local")).thenReturn(Optional.of(disabled));
        when(userRepository.findByEmail("nobody@stark.local")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(any(), any())).thenReturn(true);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("disabled@stark.local", "pw"), IP))
                    .isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@stark.local", "pw"), IP))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(loginAttemptService.remainingLock("disabled@stark.local", "198.51.100.1")).isPositive();
        assertThat(loginAttemptService.remainingLock("nobody@stark.local", "198.51.100.1")).isPositive();
    }

    @Test
    void successfulLoginClearsTheEmailCount() {
        User user = user("officer@stark.local");
        when(userRepository.findByEmail("officer@stark.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        when(passwordEncoder.matches("right", "hash")).thenReturn(true);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("officer@stark.local", "wrong"), IP))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(authService.login(new LoginRequest("officer@stark.local", "right"), IP).accessToken())
                .isNotBlank();

        assertThatThrownBy(() -> authService.login(new LoginRequest("officer@stark.local", "wrong"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(loginAttemptService.remainingLock("officer@stark.local", IP)).as("1 failure after the success")
                .isZero();
    }

    /** Review finding F1 / SEC-4: at counter = limit - 1, parallel logins get exactly one password check. */
    @Test
    void parallelLoginsCannotTestMorePasswordsThanTheLimit() throws Exception {
        User user = user("officer@stark.local");
        when(userRepository.findByEmail("officer@stark.local")).thenReturn(Optional.of(user));
        AtomicInteger passwordChecks = new AtomicInteger();
        when(passwordEncoder.matches(any(), any())).thenAnswer(invocation -> {
            passwordChecks.incrementAndGet();
            Thread.sleep(50); // BCrypt is slow: the other requests arrive while this one is checking
            return false;
        });
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("officer@stark.local", "wrong"), IP))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        passwordChecks.set(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Class<?>>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                outcomes.add(executor.submit(() -> {
                    start.await();
                    try {
                        authService.login(new LoginRequest("officer@stark.local", "guess"), IP);
                        return null;
                    } catch (RuntimeException ex) {
                        return ex.getClass();
                    }
                }));
            }
            start.countDown();
            List<Class<?>> results = new ArrayList<>();
            for (Future<Class<?>> outcome : outcomes) {
                results.add(outcome.get(10, TimeUnit.SECONDS));
            }

            assertThat(passwordChecks.get()).as("only the 5th attempt reaches the password check").isEqualTo(1);
            assertThat(results).filteredOn(TooManyLoginAttemptsException.class::equals).hasSize(9);
            assertThat(results).filteredOn(InvalidCredentialsException.class::equals).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aDatabaseFailureDuringLoginDoesNotCountAsAFailedAttempt() {
        when(userRepository.findByEmail("officer@stark.local")).thenThrow(new IllegalStateException("db down"));

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("officer@stark.local", "pw"), IP))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(loginAttemptService.remainingLock("officer@stark.local", IP)).isZero();
    }

    private static User user(String email) {
        User user = new User(email, "hash", "Officer", Role.STAFF);
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }
}
