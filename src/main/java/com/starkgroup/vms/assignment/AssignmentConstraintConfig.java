package com.starkgroup.vms.assignment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.UniqueConstraintRule;

/**
 * BR-06 safety net: a violation of the partial unique indexes on open assignments anywhere in the application
 * is reported as the same 409 driver-already-assigned problem as the check in {@link AssignmentService}.
 */
@Configuration
public class AssignmentConstraintConfig {

    @Bean
    public UniqueConstraintRule openVehicleAssignmentRule() {
        return rule(VehicleDriverAssignment.OPEN_VEHICLE_UNIQUE_INDEX);
    }

    @Bean
    public UniqueConstraintRule openDriverAssignmentRule() {
        return rule(VehicleDriverAssignment.OPEN_DRIVER_UNIQUE_INDEX);
    }

    private static UniqueConstraintRule rule(String indexName) {
        return new UniqueConstraintRule(indexName, ApiErrorType.DRIVER_ALREADY_ASSIGNED,
                AssignmentService.DRIVER_ALREADY_ASSIGNED_TITLE, AssignmentService.DRIVER_ID_FIELD,
                AssignmentService.CONCURRENT_ASSIGNMENT_MESSAGE);
    }
}
