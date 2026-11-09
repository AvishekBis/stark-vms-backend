package com.starkgroup.vms.vehicle.dto;

import com.starkgroup.vms.common.validation.ManufactureYear;
import com.starkgroup.vms.vehicle.FuelType;
import com.starkgroup.vms.vehicle.VehicleType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/v1/vehicles/{id} (FR-V03). Same fields and rules as {@link CreateVehicleRequest} plus the
 * required {@code version} of the vehicle as the client loaded it (ADR 0023, contract 6.2); status is not editable
 * here - it gets its own endpoint in v2.0 (FR-V07).
 */
public record UpdateVehicleRequest(
        @Schema(example = "DHAKA METRO-GA 11-2345")
        @NotBlank(message = "Vehicle number is required.")
        @Size(max = 40, message = "Vehicle number must be at most 40 characters.")
        @Pattern(regexp = VehicleRequest.VEHICLE_NUMBER_PATTERN,
                message = "Vehicle number must contain at least one letter or digit.")
        String vehicleNumber,

        @Schema(example = "CAR")
        @NotNull(message = "Vehicle type is required.")
        VehicleType vehicleType,

        @Schema(example = "Toyota")
        @Size(max = 50, message = "Brand must be at most 50 characters.")
        String brand,

        @Schema(example = "Axio")
        @Size(max = 50, message = "Model must be at most 50 characters.")
        String model,

        @Schema(example = "2019")
        @ManufactureYear
        Integer manufactureYear,

        @Schema(example = "White")
        @Size(max = 30, message = "Color must be at most 30 characters.")
        String color,

        @Schema(example = "OCTANE")
        FuelType fuelType,

        @Schema(example = "3")
        @NotNull(message = "Version is required.")
        Long version) implements VehicleRequest {
}
