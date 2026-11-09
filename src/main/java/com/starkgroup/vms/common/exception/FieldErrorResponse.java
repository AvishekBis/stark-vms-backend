package com.starkgroup.vms.common.exception;

/**
 * One entry of the Problem Details {@code errors} array.
 */
public record FieldErrorResponse(String field, String message) {
}
