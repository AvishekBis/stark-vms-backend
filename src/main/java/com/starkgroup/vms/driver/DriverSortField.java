package com.starkgroup.vms.driver;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;

/**
 * Whitelist of sortable driver fields: API name to entity property (contract 6.3).
 */
public enum DriverSortField {
    FULL_NAME("fullName", "fullName"),
    LICENSE_EXPIRY("licenseExpiry", "licenseExpiry"),
    CREATED_AT("createdAt", "createdAt");

    public static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, FULL_NAME.property);

    /** Secondary order so pages are stable when the primary values are equal. */
    public static final Sort TIE_BREAKER = Sort.by(Sort.Direction.ASC, "id");

    private static final Map<String, String> API_TO_PROPERTY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(field -> field.apiName, field -> field.property));

    private final String apiName;
    private final String property;

    DriverSortField(String apiName, String property) {
        this.apiName = apiName;
        this.property = property;
    }

    public static Map<String, String> apiToProperty() {
        return API_TO_PROPERTY;
    }
}
