package com.starkgroup.vms.auth;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The one password rule (ADR 0024), used for new accounts, admin resets, own changes and the bootstrap accounts:
 * at least {@value #MIN_LENGTH} characters and at most {@value #MAX_BYTES} bytes in UTF-8. BCrypt only reads the
 * first 72 bytes, and Spring Security 6.5 refuses longer input, so the API checks this first and answers 400.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;
    public static final String TOO_SHORT = "Password must be at least " + MIN_LENGTH + " characters.";
    public static final String TOO_LONG = "Password must be at most " + MAX_BYTES + " bytes (UTF-8).";

    private PasswordPolicy() {
    }

    /**
     * @return the violated rule's message, or empty when the password is acceptable ({@code null} is left to
     * {@code @NotBlank})
     */
    public static Optional<String> violation(String password) {
        if (password == null) {
            return Optional.empty();
        }
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return Optional.of(TOO_SHORT);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of(TOO_LONG);
        }
        return Optional.empty();
    }
}
