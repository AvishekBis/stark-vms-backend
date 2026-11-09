package com.starkgroup.vms.driver;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Driver phone numbers (contract 6.3): trim, remove spaces, hyphens and parentheses, and turn a leading
 * {@code +880} into {@code 0}. A valid result is a Bangladeshi mobile number such as {@code 01711000001}
 * (pattern {@code ^0\d{10}$}). The normalized value is what is stored and what must be unique.
 */
public final class DriverPhone {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s()\\-]");
    private static final Pattern VALID = Pattern.compile("^0\\d{10}$");
    private static final String COUNTRY_PREFIX = "+880";

    private DriverPhone() {
    }

    public static String normalize(String raw) {
        Objects.requireNonNull(raw, "phone must not be null");
        String compact = SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (compact.startsWith(COUNTRY_PREFIX)) {
            return "0" + compact.substring(COUNTRY_PREFIX.length());
        }
        return compact;
    }

    public static boolean isValid(String raw) {
        return raw != null && VALID.matcher(normalize(raw)).matches();
    }
}
