package com.starkgroup.vms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * ADR 0024 password policy: 8 characters to 72 bytes (BCrypt limit), also for the bootstrap accounts.
 */
class PasswordPolicyTest {

    @Test
    void eightCharactersIsTheMinimum() {
        assertThat(PasswordPolicy.violation("Abc@123")).contains("Password must be at least 8 characters.");
        assertThat(PasswordPolicy.violation("Abc@1234")).isEmpty();
    }

    @Test
    void seventyTwoUtf8BytesIsTheMaximum() {
        assertThat(PasswordPolicy.violation("a".repeat(72))).isEmpty();
        assertThat(PasswordPolicy.violation("a".repeat(73))).contains("Password must be at most 72 bytes (UTF-8).");
        // 25 Bengali letters = 25 characters but 75 bytes in UTF-8.
        assertThat(PasswordPolicy.violation("ক".repeat(25))).contains(PasswordPolicy.TOO_LONG);
    }

    @Test
    void nullIsLeftToNotBlank() {
        assertThat(PasswordPolicy.violation(null)).isEmpty();
    }

    @Test
    void theBcryptEncoderReallyRefusesMoreThan72Bytes() {
        // Why the API checks first: Spring Security 6.5 throws instead of silently truncating.
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> encoder.encode("a".repeat(73)));
    }

    @Test
    void bootstrapRefusesAPasswordThatBreaksThePolicy() {
        BootstrapProperties properties = new BootstrapProperties("admin@stark.local", "a".repeat(73), "Fleet Admin",
                null, null, "Fleet Officer");
        AdminBootstrap bootstrap = new AdminBootstrap(properties, mock(UserRepository.class),
                new BCryptPasswordEncoder(4));

        assertThatIllegalStateException().isThrownBy(() -> bootstrap.run(null))
                .withMessageContaining("72 bytes");
    }
}
