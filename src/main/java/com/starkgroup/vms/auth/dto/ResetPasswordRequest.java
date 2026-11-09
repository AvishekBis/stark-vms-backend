package com.starkgroup.vms.auth.dto;

import com.starkgroup.vms.auth.ValidPassword;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /api/v1/users/{id}/password (ADMIN reset, contract 7.3.3).
 */
public record ResetPasswordRequest(
        @Schema(example = "Temp@2026x", description = "At least 8 characters, at most 72 bytes in UTF-8")
        @NotBlank(message = "New password is required.")
        @ValidPassword
        String newPassword) {
}
