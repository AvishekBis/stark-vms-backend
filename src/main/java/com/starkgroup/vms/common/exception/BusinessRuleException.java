package com.starkgroup.vms.common.exception;

import java.net.URI;

/**
 * A request is well-formed but violates a business rule (e.g. BR-05, BR-06, BR-10); mapped to 409.
 * A specific Problem Details {@code type} and {@code title} can be given; when {@code field} is set the
 * response also carries an {@code errors} entry for that field (e.g. {@code driverId} for TC-10).
 */
public class BusinessRuleException extends RuntimeException {

    private static final String DEFAULT_TITLE = "Business rule violated";

    private final URI type;
    private final String title;
    private final String field;

    public BusinessRuleException(String message) {
        this(ApiErrorType.CONFLICT, DEFAULT_TITLE, null, message);
    }

    public BusinessRuleException(URI type, String title, String message) {
        this(type, title, null, message);
    }

    public BusinessRuleException(URI type, String title, String field, String message) {
        super(message);
        this.type = type;
        this.title = title;
        this.field = field;
    }

    public URI getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    /** Request field the conflict refers to, or {@code null}. */
    public String getField() {
        return field;
    }
}
