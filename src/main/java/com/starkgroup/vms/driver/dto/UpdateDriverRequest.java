package com.starkgroup.vms.driver.dto;

import java.time.LocalDate;

import com.starkgroup.vms.driver.DriverStatus;
import com.starkgroup.vms.driver.ValidDriverPhone;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/v1/drivers/{id} (FR-DR01, contract 6.3 and 7.2.2): the fields and rules of {@link DriverRequest}
 * plus the required {@code version} of the driver as the client loaded it (ADR 0023).
 */
public record UpdateDriverRequest(
        @Schema(example = "Rahim Uddin")
        @NotBlank(message = "Full name is required.")
        @Size(max = 100, message = "Full name must be at most 100 characters.")
        String fullName,

        @Schema(example = "01711-000001", description = "Stored normalized, e.g. 01711000001; +880 becomes 0")
        @NotBlank(message = "Phone is required.")
        @Size(max = 20, message = "Phone must be at most 20 characters.")
        @ValidDriverPhone
        String phone,

        @Schema(example = "DK0123456L00001")
        @NotBlank(message = "Licence number is required.")
        @Size(max = 40, message = "Licence number must be at most 40 characters.")
        @Pattern(regexp = DriverRequest.LICENSE_NUMBER_PATTERN,
                message = "Licence number must contain at least one letter or digit.")
        String licenseNumber,

        @Schema(example = "2027-03-31")
        @NotNull(message = "Licence expiry date is required.")
        LocalDate licenseExpiry,

        @Schema(example = "ACTIVE", description = "Optional, default ACTIVE")
        DriverStatus status,

        @Schema(example = "5")
        @NotNull(message = "Version is required.")
        Long version) implements DriverFields {
}
