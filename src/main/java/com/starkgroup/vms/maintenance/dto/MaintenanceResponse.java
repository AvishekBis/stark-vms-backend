package com.starkgroup.vms.maintenance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.starkgroup.vms.maintenance.MaintenanceType;

import io.swagger.v3.oas.annotations.media.Schema;

public record MaintenanceResponse(
        @Schema(example = "57") Long id,
        @Schema(example = "12") Long vehicleId,
        @Schema(example = "DHK-1234") String vehicleNumber,
        @Schema(example = "2026-11-10") LocalDate serviceDate,
        @Schema(example = "OIL_CHANGE") MaintenanceType maintenanceType,
        @Schema(example = "3500.0") BigDecimal cost,
        @Schema(example = "Navana Service Centre", nullable = true) String vendorName,
        @Schema(example = "45210", nullable = true) Integer odometerKm,
        @Schema(example = "Synthetic oil", nullable = true) String notes,
        Instant createdAt,
        Instant updatedAt,
        @Schema(example = "staff@stark.local") String createdBy,
        @Schema(example = "0", description = "Optimistic-locking version; send it back in PUT (ADR 0023)")
        Long version) {
}
