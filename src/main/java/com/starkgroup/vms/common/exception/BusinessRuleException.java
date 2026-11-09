package com.starkgroup.vms.common.exception;

/**
 * A request is well-formed but violates a business rule (e.g. BR-05, BR-10); mapped to 409.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
