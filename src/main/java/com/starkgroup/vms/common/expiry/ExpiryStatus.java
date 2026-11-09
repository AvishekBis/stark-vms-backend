package com.starkgroup.vms.common.expiry;

/**
 * BR-08: expiry state of a document or a driver licence. Never stored - always computed from the
 * expiry date by {@link ExpiryCalculator}, so it cannot go stale.
 */
public enum ExpiryStatus {
    VALID,
    EXPIRING_SOON,
    EXPIRED
}
