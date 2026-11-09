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
 * Body of POST /api/v1/drivers (FR-DR01, contract 6.3). PUT uses {@link UpdateDriverRequest} (same fields plus
 * {@code version}, contract 7.2.2).
 */
public record DriverRequest(
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
        @Pattern(regexp = LICENSE_NUMBER_PATTERN, message = "Licence number must contain at least one letter or digit.")
        String licenseNumber,

        @Schema(example = "2027-03-31")
        @NotNull(message = "Licence expiry date is required.")
        LocalDate licenseExpiry,

        @Schema(example = "ACTIVE", description = "Optional, default ACTIVE")
        DriverStatus status) implements DriverFields {

    /** At least one letter or digit; an all-blank value is reported by @NotBlank only. */
    public static final String LICENSE_NUMBER_PATTERN = "(?s)\\s*|.*[\\p{L}\\p{N}].*";
}
