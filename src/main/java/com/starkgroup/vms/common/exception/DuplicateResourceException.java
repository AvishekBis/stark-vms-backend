package com.starkgroup.vms.common.exception;

import java.net.URI;

/**
 * A unique business key is already taken; mapped to 409 with a field error so the UI can show
 * the message next to the right input.
 */
public class DuplicateResourceException extends RuntimeException {

    private final URI type;
    private final String title;
    private final String field;

    public DuplicateResourceException(URI type, String title, String field, String message) {
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

    public String getField() {
        return field;
    }
}
