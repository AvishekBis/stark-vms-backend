package com.starkgroup.vms.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login throttling thresholds (ADR 0022), bound from {@code app.security.login.*} (environment variables
 * APP_SECURITY_LOGIN_MAX_FAILURES_PER_EMAIL, APP_SECURITY_LOGIN_MAX_FAILURES_PER_IP, APP_SECURITY_LOGIN_LOCKOUT).
 * Start-up fails on a value of 0 or less.
 *
 * @param maxFailuresPerEmail failed logins for one e-mail within {@code lockout} that lock the e-mail
 * @param maxFailuresPerIp    failed logins from one client IP within {@code lockout} that block the IP
 * @param lockout             lock length, and also the window in which failures are counted
 */
@ConfigurationProperties(prefix = "app.security.login")
public record LoginThrottleProperties(
        @DefaultValue("5") int maxFailuresPerEmail,
        @DefaultValue("20") int maxFailuresPerIp,
        @DefaultValue("15m") Duration lockout) {

    public LoginThrottleProperties {
        if (maxFailuresPerEmail <= 0 || maxFailuresPerIp <= 0) {
            throw new IllegalStateException(
                    "app.security.login.max-failures-per-email and max-failures-per-ip must be greater than 0.");
        }
        if (lockout == null || lockout.isZero() || lockout.isNegative()) {
            throw new IllegalStateException("app.security.login.lockout must be greater than 0.");
        }
    }
}
