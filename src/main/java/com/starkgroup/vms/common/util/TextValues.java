package com.starkgroup.vms.common.util;

/**
 * Small helpers for optional text fields: trimmed, and blank stored as {@code null}.
 */
public final class TextValues {

    private TextValues() {
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
