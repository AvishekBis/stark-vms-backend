package com.starkgroup.vms.auth.dto;

import java.time.Instant;

import com.starkgroup.vms.auth.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A user account as ADMINs see it (contract 7.3.1, "UserResponse" there; named differently here because
 * {@link UserResponse} is the 4-field user of login and /auth/me). Never contains the password hash or when the
 * password was changed.
 */
@Schema(name = "UserAccountResponse")
public record UserAccountResponse(
        @Schema(example = "7") Long id,
        @Schema(example = "officer2@stark.local") String email,
        @Schema(example = "Second Officer") String fullName,
        @Schema(example = "STAFF") Role role,
        @Schema(example = "true") boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        @Schema(example = "admin@stark.local", description = "\"system\" for accounts created at start-up")
        String createdBy,
        @Schema(example = "0") Long version) {
}
