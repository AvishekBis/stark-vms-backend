package com.starkgroup.vms.vehicle;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;

/**
 * Whitelist of sortable vehicle fields: API name to entity property (FR-V09).
 */
public enum VehicleSortField {
    VEHICLE_NUMBER("vehicleNumber", "vehicleNumber"),
    MANUFACTURE_YEAR("manufactureYear", "manufactureYear"),
    CREATED_AT("createdAt", "createdAt");

    public static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, CREATED_AT.property);

    /** Secondary order so pages are stable when the primary values are equal. */
    public static final Sort TIE_BREAKER = Sort.by(Sort.Direction.DESC, "id");

    private static final Map<String, String> API_TO_PROPERTY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(field -> field.apiName, field -> field.property));

    private final String apiName;
    private final String property;

    VehicleSortField(String apiName, String property) {
        this.apiName = apiName;
        this.property = property;
    }

    public static Map<String, String> apiToProperty() {
        return API_TO_PROPERTY;
    }
}
