package com.starkgroup.vms.driver.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.starkgroup.vms.common.expiry.ExpiryStatus;
import com.starkgroup.vms.driver.DriverStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A driver with the computed licence expiry state (BR-08) and the current vehicle (null when none).
 */
public record DriverResponse(
        @Schema(example = "4") Long id,
        @Schema(example = "Rahim Uddin") String fullName,
        @Schema(example = "01711000001") String phone,
        @Schema(example = "DK0123456L00001") String licenseNumber,
        @Schema(example = "2027-03-31") LocalDate licenseExpiry,
        @Schema(example = "VALID") ExpiryStatus licenseStatus,
        @Schema(example = "147") int daysUntilLicenseExpiry,
        @Schema(example = "ACTIVE") DriverStatus status,
        @Schema(nullable = true) CurrentVehicle currentVehicle,
        Instant createdAt,
        Instant updatedAt,
        @Schema(example = "admin@stark.local") String createdBy,
        @Schema(example = "5", description = "Optimistic-locking version; send it back in PUT (ADR 0023)")
        Long version) {

    public record CurrentVehicle(
            @Schema(example = "12") Long id,
            @Schema(example = "DHK-1234") String vehicleNumber) {
    }
}
