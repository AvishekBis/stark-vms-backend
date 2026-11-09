package com.starkgroup.vms.maintenance;

/**
 * FR-M01 maintenance types (also enforced by a DB CHECK constraint).
 */
public enum MaintenanceType {
    ROUTINE_SERVICE,
    OIL_CHANGE,
    TYRE,
    BRAKE,
    ENGINE,
    ELECTRICAL,
    BODY_WORK,
    INSPECTION,
    REPAIR,
    OTHER
}
