package com.starkgroup.vms.vehicle.dto;

import java.time.Instant;

import com.starkgroup.vms.vehicle.FuelType;
import com.starkgroup.vms.vehicle.VehicleStatus;
import com.starkgroup.vms.vehicle.VehicleType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A vehicle as returned by the API. {@code currentDriver} is null when no driver is assigned (contract 6.2);
 * {@code version} is sent back in PUT (contract 7.2).
 */
public record VehicleResponse(
        Long id,
        String vehicleNumber,
        VehicleType vehicleType,
        String brand,
        String model,
        Integer manufactureYear,
        String color,
        FuelType fuelType,
        VehicleStatus status,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        @Schema(nullable = true) CurrentDriver currentDriver,
        @Schema(example = "3", description = "Optimistic-locking version; send it back in PUT (ADR 0023)")
        Long version) {

    public record CurrentDriver(
            @Schema(example = "4") Long id,
            @Schema(example = "Rahim Uddin") String fullName,
            @Schema(example = "01711000001") String phone) {
    }
}
