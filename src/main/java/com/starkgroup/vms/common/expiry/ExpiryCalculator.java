package com.starkgroup.vms.common.expiry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.config.ClockConfig;

/**
 * BR-08, shared by documents (FR-D02) and driver licences (FR-X02):
 * <ul>
 *   <li>EXPIRED if the expiry date is before today,</li>
 *   <li>EXPIRING_SOON if today &lt;= expiry date &lt;= today + {@value #EXPIRING_SOON_DAYS} days,</li>
 *   <li>VALID otherwise.</li>
 * </ul>
 * "Today" is the date of the injected {@link Clock} in the business zone Asia/Dhaka, whatever zone the
 * clock (or the server) uses - so "expires today" switches at midnight in Dhaka, not UTC.
 */
@Component
public class ExpiryCalculator {

    public static final int EXPIRING_SOON_DAYS = 30;

    private final Clock clock;

    public ExpiryCalculator(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ClockConfig.BUSINESS_ZONE));
    }

    /** Last expiry date that still counts as EXPIRING_SOON. */
    public LocalDate expiringSoonUntil() {
        return today().plusDays(EXPIRING_SOON_DAYS);
    }

    public ExpiryInfo evaluate(LocalDate expiryDate) {
        Objects.requireNonNull(expiryDate, "expiry date must not be null");
        LocalDate today = today();
        int days = Math.toIntExact(ChronoUnit.DAYS.between(today, expiryDate));
        return new ExpiryInfo(statusFor(days), days);
    }

    public ExpiryStatus statusOf(LocalDate expiryDate) {
        return evaluate(expiryDate).status();
    }

    public int daysUntil(LocalDate expiryDate) {
        return evaluate(expiryDate).daysUntilExpiry();
    }

    private static ExpiryStatus statusFor(int daysUntilExpiry) {
        if (daysUntilExpiry < 0) {
            return ExpiryStatus.EXPIRED;
        }
        return daysUntilExpiry <= EXPIRING_SOON_DAYS ? ExpiryStatus.EXPIRING_SOON : ExpiryStatus.VALID;
    }
}
