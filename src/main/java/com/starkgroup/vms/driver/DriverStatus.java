package com.starkgroup.vms.driver;

/**
 * FR-DR01: only ACTIVE drivers can be assigned to a vehicle (BR-05). Also enforced by a DB CHECK constraint.
 */
public enum DriverStatus {
    ACTIVE,
    INACTIVE
}
