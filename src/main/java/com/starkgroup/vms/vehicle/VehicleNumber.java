package com.starkgroup.vms.vehicle;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * BR-01: vehicle numbers are compared after normalization - trim, uppercase, remove all
 * whitespace and hyphens. "DHK-1234", "dhk 1234" and " DHK1234 " all become "DHK1234".
 */
public final class VehicleNumber {

    private static final Pattern WHITESPACE_AND_HYPHENS = Pattern.compile("[\\s-]");

    private VehicleNumber() {
    }

    public static String normalize(String raw) {
        Objects.requireNonNull(raw, "vehicle number must not be null");
        return WHITESPACE_AND_HYPHENS.matcher(raw.trim().toUpperCase(Locale.ROOT)).replaceAll("");
    }
}
