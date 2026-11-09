package com.starkgroup.vms.auth;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;

/**
 * Whitelist of sortable user fields: API name to entity property (contract 7.3.2).
 */
public enum UserSortField {
    EMAIL("email", "email"),
    FULL_NAME("fullName", "fullName"),
    CREATED_AT("createdAt", "createdAt");

    public static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, EMAIL.property);

    /** Secondary order so pages are stable when the primary values are equal. */
    public static final Sort TIE_BREAKER = Sort.by(Sort.Direction.ASC, "id");

    private static final Map<String, String> API_TO_PROPERTY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(field -> field.apiName, field -> field.property));

    private final String apiName;
    private final String property;

    UserSortField(String apiName, String property) {
        this.apiName = apiName;
        this.property = property;
    }

    public static Map<String, String> apiToProperty() {
        return API_TO_PROPERTY;
    }
}
