package com.starkgroup.vms.common.exception;

/**
 * Invalid request parameter that is not a DTO field (e.g. an unknown sort field); mapped to 400.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
