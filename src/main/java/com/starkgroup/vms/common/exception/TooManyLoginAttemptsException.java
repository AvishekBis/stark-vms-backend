package com.starkgroup.vms.common.exception;

import java.time.Duration;
import java.util.Locale;

/**
 * Login refused because the e-mail is locked or the client IP is blocked after too many failed logins
 * (ADR 0022); mapped to 429 too-many-login-attempts with a {@code Retry-After} header.
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    public static final String TITLE = "Too many login attempts";
    private static final long SECONDS_PER_MINUTE = 60;

    private final long retryAfterSeconds;

    /**
     * @param remainingLock time until the lock ends; rounded up to whole seconds, at least 1
     */
    public TooManyLoginAttemptsException(Duration remainingLock) {
        this(roundUpToSeconds(remainingLock));
    }

    private TooManyLoginAttemptsException(long retryAfterSeconds) {
        super(messageFor(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Value of the {@code Retry-After} header. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static long roundUpToSeconds(Duration remainingLock) {
        long seconds = remainingLock.getSeconds() + (remainingLock.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    private static String messageFor(long retryAfterSeconds) {
        long minutes = (retryAfterSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE;
        String unit = minutes == 1 ? "minute" : "minutes";
        return String.format(Locale.ROOT, "Too many failed login attempts. Try again in %d %s.", minutes, unit);
    }
}
