package com.starkgroup.vms.assignment;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.vehicle.VehicleUsage;

/** BR-10: a vehicle with driver assignments (open or closed) is not deleted. */
@Component
class AssignmentVehicleUsage implements VehicleUsage {

    private final VehicleDriverAssignmentRepository assignmentRepository;

    AssignmentVehicleUsage(VehicleDriverAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    public boolean isUsedBy(Long vehicleId) {
        return assignmentRepository.existsByVehicleId(vehicleId);
    }
}
