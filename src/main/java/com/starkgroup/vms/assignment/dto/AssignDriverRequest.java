package com.starkgroup.vms.assignment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of PUT /api/v1/vehicles/{id}/driver (FR-DR02).
 */
public record AssignDriverRequest(
        @Schema(example = "4")
        @NotNull(message = "Driver is required.")
        Long driverId) {
}
