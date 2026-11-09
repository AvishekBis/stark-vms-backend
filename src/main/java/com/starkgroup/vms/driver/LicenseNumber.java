package com.starkgroup.vms.driver;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Driving licence numbers are compared after normalization - trim, uppercase, remove whitespace and hyphens
 * (contract 6.3) - so {@code dk-0123456 l00001} and {@code DK0123456L00001} are the same licence.
 */
public final class LicenseNumber {

    private static final Pattern WHITESPACE_AND_HYPHENS = Pattern.compile("[\\s-]");

    private LicenseNumber() {
    }

    public static String normalize(String raw) {
        Objects.requireNonNull(raw, "licence number must not be null");
        return WHITESPACE_AND_HYPHENS.matcher(raw.trim().toUpperCase(Locale.ROOT)).replaceAll("");
    }
}
