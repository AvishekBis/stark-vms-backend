package com.starkgroup.vms.auth.dto;

import com.starkgroup.vms.auth.Role;

public record UserResponse(Long id, String email, String fullName, Role role) {
}
