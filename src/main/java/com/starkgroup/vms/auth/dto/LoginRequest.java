package com.starkgroup.vms.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Schema(example = "admin@stark.local")
        @NotBlank(message = "Email is required.")
        @Size(max = 255, message = "Email must be at most 255 characters.")
        String email,

        @Schema(example = "Admin@12345")
        @NotBlank(message = "Password is required.")
        @Size(max = 100, message = "Password must be at most 100 characters.")
        String password) {
}
