package com.starkgroup.vms.assignment;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.assignment.dto.AssignmentResponse;

/**
 * Entity to DTO mapping. Call inside a transaction: vehicle and driver are lazy associations.
 */
@Component
public class AssignmentMapper {

    public AssignmentResponse toResponse(VehicleDriverAssignment assignment) {
        return new AssignmentResponse(
                assignment.getId(),
                assignment.getVehicle().getId(),
                assignment.getVehicle().getVehicleNumber(),
                assignment.getDriver().getId(),
                assignment.getDriver().getFullName(),
                assignment.getAssignedAt(),
                assignment.getUnassignedAt(),
                assignment.isCurrent(),
                assignment.getCreatedBy());
    }
}
