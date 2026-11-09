package com.starkgroup.vms.common.exception;

import java.net.URI;

/**
 * Maps a named database unique constraint to the 409 a service would return for the same
 * conflict, so a lost race ends in the same response as the service-level check. Each feature
 * declares its own rules as beans (vehicles now; driver phone and licence number in v2.0), and
 * the {@link GlobalExceptionHandler} stays free of feature imports.
 */
public record UniqueConstraintRule(String constraintName, URI type, String title, String field, String message) {

    public DuplicateResourceException toException() {
        return new DuplicateResourceException(type, title, field, message);
    }
}
