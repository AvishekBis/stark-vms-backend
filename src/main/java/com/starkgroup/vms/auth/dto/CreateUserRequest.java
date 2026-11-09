package com.starkgroup.vms.auth.dto;

import com.starkgroup.vms.auth.Role;
import com.starkgroup.vms.auth.ValidPassword;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/users (ADMIN, contract 7.3.3). The e-mail is stored trimmed and lower-cased, so surrounding
 * spaces are accepted by the format rule.
 */
public record CreateUserRequest(
        @Schema(example = "officer2@stark.local")
        @NotBlank(message = "E-mail is required.")
        @Size(max = 255, message = "E-mail must be at most 255 characters.")
        @Pattern(regexp = EMAIL_PATTERN, message = "E-mail must be a valid e-mail address.")
        String email,

        @Schema(example = "Second Officer")
        @NotBlank(message = "Full name is required.")
        @Size(max = 100, message = "Full name must be at most 100 characters.")
        String fullName,

        @Schema(example = "STAFF")
        @NotNull(message = "Role is required.")
        Role role,

        @Schema(example = "Welcome@2026", description = "At least 8 characters, at most 72 bytes in UTF-8")
        @NotBlank(message = "Password is required.")
        @ValidPassword
        String password) {

    /** local@domain.tld, surrounding spaces allowed (trimmed later); a blank value is reported by @NotBlank only. */
    public static final String EMAIL_PATTERN = "(?s)\\s*|\\s*[^@\\s]+@[^@\\s]+\\.[^@\\s]+\\s*";
}
