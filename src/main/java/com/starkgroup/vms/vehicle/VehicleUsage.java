package com.starkgroup.vms.vehicle;

/**
 * A feature that keeps records referencing vehicles (documents, maintenance, driver assignments). Each owning
 * feature answers BR-10 for its own data, so the vehicle feature never reads another feature's tables.
 */
@FunctionalInterface
public interface VehicleUsage {

    /** True when the feature holds at least one record of the vehicle. */
    boolean isUsedBy(Long vehicleId);
}
