package com.starkgroup.vms.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Initial accounts created at start-up (guideline section 13: first ADMIN from environment
 * variables, never from a migration). Bound from APP_ADMIN_* / APP_STAFF_* via application.yml.
 */
@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapProperties(
        String adminEmail,
        String adminPassword,
        @DefaultValue("Fleet Admin") String adminFullName,
        String staffEmail,
        String staffPassword,
        @DefaultValue("Fleet Officer") String staffFullName) {
}
