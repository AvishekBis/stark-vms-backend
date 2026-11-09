package com.starkgroup.vms.common.exception;

/**
 * Login failed. The message is deliberately generic (FR-AUTH-01): it never says whether the
 * e-mail or the password was wrong.
 */
public class InvalidCredentialsException extends RuntimeException {

    public static final String GENERIC_MESSAGE = "Invalid email or password.";

    public InvalidCredentialsException() {
        super(GENERIC_MESSAGE);
    }
}
