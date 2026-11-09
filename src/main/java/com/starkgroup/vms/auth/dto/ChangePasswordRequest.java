package com.starkgroup.vms.auth.dto;

import com.starkgroup.vms.auth.ValidPassword;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/v1/auth/me/password (any role, contract 7.3.4).
 */
public record ChangePasswordRequest(
        @Schema(example = "Staff@12345")
        @NotBlank(message = "Current password is required.")
        @Size(max = 100, message = "Current password must be at most 100 characters.")
        String currentPassword,

        @Schema(example = "N3w-Staff-Pass", description = "At least 8 characters, at most 72 bytes in UTF-8")
        @NotBlank(message = "New password is required.")
        @ValidPassword
        String newPassword) {
}
