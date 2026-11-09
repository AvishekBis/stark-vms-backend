package com.starkgroup.vms.vehicle.dto;

import com.starkgroup.vms.vehicle.VehicleStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of PATCH /api/v1/vehicles/{id}/status (FR-V07, ADMIN only).
 */
public record VehicleStatusRequest(
        @Schema(example = "MAINTENANCE")
        @NotNull(message = "Status is required.")
        VehicleStatus status) {
}
