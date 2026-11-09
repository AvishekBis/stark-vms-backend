package com.starkgroup.vms.auth.dto;

/**
 * @param expiresIn token lifetime in seconds
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    public static final String BEARER = "Bearer";
}
