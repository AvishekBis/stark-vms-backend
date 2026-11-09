package com.starkgroup.vms.assignment.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One driver-vehicle assignment period (contract 6.4). {@code unassignedAt} is null while it is current.
 */
public record AssignmentResponse(
        @Schema(example = "9") Long id,
        @Schema(example = "12") Long vehicleId,
        @Schema(example = "DHK-1234") String vehicleNumber,
        @Schema(example = "4") Long driverId,
        @Schema(example = "Rahim Uddin") String driverName,
        @Schema(example = "2026-11-18T04:00:00Z") Instant assignedAt,
        @Schema(nullable = true) Instant unassignedAt,
        @Schema(example = "true") boolean current,
        @Schema(example = "admin@stark.local") String assignedBy) {
}
