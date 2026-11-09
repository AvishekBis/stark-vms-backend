package com.starkgroup.vms.auth.dto;

import com.starkgroup.vms.auth.Role;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/v1/users/{id} (ADMIN, contract 7.3.3). All fields are required; the e-mail cannot be changed
 * (an {@code email} in the body is ignored). {@code version} is the version of the user as the client loaded it
 * (ADR 0023).
 */
public record UpdateUserRequest(
        @Schema(example = "Second Officer")
        @NotBlank(message = "Full name is required.")
        @Size(max = 100, message = "Full name must be at most 100 characters.")
        String fullName,

        @Schema(example = "STAFF")
        @NotNull(message = "Role is required.")
        Role role,

        @Schema(example = "true")
        @NotNull(message = "Enabled is required.")
        Boolean enabled,

        @Schema(example = "0")
        @NotNull(message = "Version is required.")
        Long version) {
}
