package com.starkgroup.vms.vehicle;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.UniqueConstraintRule;

/**
 * BR-01 safety net: a violation of {@code uk_vehicles_number_key} anywhere in the application is
 * reported as the same 409 duplicate-vehicle-number problem as the check in {@link VehicleService}.
 */
@Configuration
public class VehicleConstraintConfig {

    @Bean
    public UniqueConstraintRule vehicleNumberUniqueRule() {
        return new UniqueConstraintRule(Vehicle.NUMBER_KEY_UNIQUE_CONSTRAINT, ApiErrorType.DUPLICATE_VEHICLE_NUMBER,
                Vehicle.DUPLICATE_NUMBER_TITLE, VehicleService.VEHICLE_NUMBER_FIELD,
                "This vehicle number is already registered.");
    }
}
