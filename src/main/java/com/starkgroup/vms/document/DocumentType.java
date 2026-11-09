package com.starkgroup.vms.document;

/**
 * FR-D01 document types (also enforced by a DB CHECK constraint).
 */
public enum DocumentType {
    REGISTRATION,
    TAX_TOKEN,
    INSURANCE,
    FITNESS_CERTIFICATE,
    ROUTE_PERMIT,
    OTHER
}
