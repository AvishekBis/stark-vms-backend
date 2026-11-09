package com.starkgroup.vms.common.exception;

/**
 * The client sent the {@code version} of a record that has changed since it was loaded (ADR 0023); mapped to
 * 409 stale-version without an {@code errors} array, because the conflict concerns the whole record.
 */
public class StaleVersionException extends RuntimeException {

    public static final String TITLE = "Record changed";

    /**
     * @param recordNoun what changed, as the user calls it: "vehicle" (v1.1 has optimistic locking on vehicles only)
     */
    public StaleVersionException(String recordNoun) {
        super(messageFor(recordNoun));
    }

    public static String messageFor(String recordNoun) {
        return "This " + recordNoun + " was changed by someone else since you opened it. Reload and try again.";
    }
}
