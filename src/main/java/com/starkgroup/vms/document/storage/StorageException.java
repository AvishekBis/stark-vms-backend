package com.starkgroup.vms.document.storage;

/**
 * The storage backend failed. Reported to clients as 503 "Storage unavailable" without internal details.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
