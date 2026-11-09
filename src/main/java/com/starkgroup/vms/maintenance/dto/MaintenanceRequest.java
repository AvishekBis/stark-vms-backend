package com.starkgroup.vms.maintenance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.starkgroup.vms.maintenance.MaintenanceType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/vehicles/{id}/maintenance (FR-M01, BR-09). PUT /api/v1/maintenance/{recordId} uses
 * {@link UpdateMaintenanceRequest} (same fields plus {@code version}, contract 7.2.2).
 * BR-09 "service date not in the future" is checked here against the application Clock (Asia/Dhaka, see
 * ValidationConfig) and again in the service.
 */
public record MaintenanceRequest(
        @Schema(example = "2026-11-10")
        @NotNull(message = "Service date is required.")
        @PastOrPresent(message = "Service date cannot be in the future.")
        LocalDate serviceDate,

        @Schema(example = "OIL_CHANGE")
        @NotNull(message = "Maintenance type is required.")
        MaintenanceType maintenanceType,

        @Schema(example = "3500.00", description = "BDT, >= 0, at most 2 decimals")
        @NotNull(message = "Cost is required.")
        @DecimalMin(value = "0.00", message = "Cost must be 0 or more.")
        @Digits(integer = 10, fraction = 2, message = "Cost must have at most 10 digits and 2 decimals.")
        BigDecimal cost,

        @Schema(example = "Navana Service Centre")
        @Size(max = 100, message = "Vendor name must be at most 100 characters.")
        String vendorName,

        @Schema(example = "45210")
        @PositiveOrZero(message = "Odometer reading must be 0 or more.")
        Integer odometerKm,

        @Schema(example = "Synthetic oil")
        @Size(max = 1000, message = "Notes must be at most 1000 characters.")
        String notes) implements MaintenanceFields {
}
