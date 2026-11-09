package com.starkgroup.vms.common.exception;

/**
 * A field failed a rule that can only be checked in the service (e.g. a date compared with "today"
 * from the injected Clock, an uploaded file's magic bytes, or the current password of the user). Mapped to the
 * same 400 validation Problem Details as a {@code @Valid} failure, with one {@code errors} entry for the field.
 * The Problem Details {@code detail} is the generic validation text unless a specific one is given.
 */
public class FieldValidationException extends RuntimeException {

    private final String field;
    private final String detail;

    public FieldValidationException(String field, String message) {
        this(field, message, null);
    }

    /**
     * @param detail Problem Details {@code detail}, or {@code null} for the generic "One or more fields are invalid."
     */
    public FieldValidationException(String field, String message, String detail) {
        super(message);
        this.field = field;
        this.detail = detail;
    }

    public String getField() {
        return field;
    }

    /** Specific Problem Details {@code detail}, or {@code null}. */
    public String getDetail() {
        return detail;
    }
}
