package com.starkgroup.vms.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS settings. {@code allowedOrigins} comes from CORS_ALLOWED_ORIGINS (comma-separated).
 * {@code allowedOriginPatterns} is only set in the local profile (any localhost port).
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins, List<String> allowedOriginPatterns) {

    private static final String WILDCARD = "*";

    public CorsProperties {
        allowedOrigins = clean(allowedOrigins);
        allowedOriginPatterns = clean(allowedOriginPatterns);
        if (allowedOrigins.contains(WILDCARD) || allowedOriginPatterns.contains(WILDCARD)) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS must list explicit origins; '*' is not allowed (guideline section 13).");
        }
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
