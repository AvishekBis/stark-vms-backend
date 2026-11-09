package com.starkgroup.vms.common.util;

import java.util.Locale;

/**
 * Builds "contains" LIKE patterns from user input. Wildcards typed by the user are escaped,
 * so a search for "50%" matches the literal text. The value is always bound as a parameter.
 */
public final class LikePatterns {

    public static final char ESCAPE_CHAR = '\\';

    private LikePatterns() {
    }

    public static String containsIgnoreCase(String term) {
        String escaped = term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
