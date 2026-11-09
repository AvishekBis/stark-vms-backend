package com.starkgroup.vms.auth;

import java.util.Locale;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.auth.LoginAttemptService.LoginAttempt;
import com.starkgroup.vms.auth.dto.LoginRequest;
import com.starkgroup.vms.auth.dto.LoginResponse;
import com.starkgroup.vms.auth.dto.UserResponse;
import com.starkgroup.vms.common.exception.InvalidCredentialsException;
import com.starkgroup.vms.common.exception.TooManyLoginAttemptsException;

/**
 * Login (FR-AUTH-01) with throttling (ADR 0022) and current-user lookup (FR-AUTH-02).
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final String timingEqualizerHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.timingEqualizerHash = passwordEncoder.encode("timing-equalizer-not-a-password");
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * ADR 0022 order: (1) normalize the e-mail, (2) reserve the attempt: refuse with 429 while the e-mail is locked
     * or the IP blocked - without checking the password - and otherwise count it before the password check, so
     * parallel requests cannot test more passwords than the limit, (3) authenticate, (4) on failure keep the count
     * and answer the generic 401, (5) on success clear the e-mail's failures (the IP gets its reserved count back).
     * Unknown and existing e-mails behave the same.
     *
     * @param clientIp the client address as seen by the backend (right-most untrusted X-Forwarded-For entry
     *                 behind Render, resolved by Tomcat's RemoteIpValve)
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        LoginAttempt attempt = loginAttemptService.tryAcquire(email, clientIp);
        if (attempt.refused()) {
            throw new TooManyLoginAttemptsException(attempt.retryAfter());
        }
        User user;
        boolean authenticated;
        try {
            user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                passwordEncoder.matches(request.password(), timingEqualizerHash);
                authenticated = false;
            } else {
                authenticated = passwordEncoder.matches(request.password(), user.getPasswordHash())
                        && user.isEnabled();
            }
        } catch (RuntimeException ex) {
            loginAttemptService.release(attempt);
            throw ex;
        }
        if (!authenticated) {
            loginAttemptService.failed(attempt);
            throw new InvalidCredentialsException();
        }
        loginAttemptService.succeeded(attempt);
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new LoginResponse(token, LoginResponse.BEARER, jwtService.expiresInSeconds(), toResponse(user));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(AuthenticatedUser principal) {
        return userRepository.findById(principal.id())
                .filter(User::isEnabled)
                .map(AuthService::toResponse)
                .orElseThrow(() -> new BadCredentialsException("User account is no longer active."));
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
