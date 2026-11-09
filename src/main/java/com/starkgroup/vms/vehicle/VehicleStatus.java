package com.starkgroup.vms.vehicle;

/**
 * BR-04: the only allowed vehicle statuses (also enforced by a DB CHECK constraint).
 */
public enum VehicleStatus {
    ACTIVE,
    INACTIVE,
    MAINTENANCE
}
