package com.starkgroup.vms.common.exception;

/**
 * Requested resource id does not exist; mapped to 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resourceName, Object id) {
        super(resourceName + " with id " + id + " was not found.");
    }
}
