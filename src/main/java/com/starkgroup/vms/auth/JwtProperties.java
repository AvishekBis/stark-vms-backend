package com.starkgroup.vms.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT settings: secret from JWT_SECRET, expiry from JWT_EXPIRATION_MINUTES (default 60).
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, @DefaultValue("60") long expirationMinutes) {
}
