package com.starkgroup.vms.common.expiry;

/**
 * Computed expiry state plus the days left ({@code 0} = expires today, negative = already expired).
 */
public record ExpiryInfo(ExpiryStatus status, int daysUntilExpiry) {
}
