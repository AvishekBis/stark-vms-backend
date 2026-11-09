package com.starkgroup.vms.auth;

/**
 * The bearer token is missing a valid signature, is malformed or has expired.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
