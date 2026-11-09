package com.starkgroup.vms.vehicle;

import org.springframework.data.jpa.domain.Specification;

import com.starkgroup.vms.common.util.LikePatterns;

/**
 * Composable, parameter-bound query criteria for the vehicle list. Status and type filters
 * (FR-V09) are added here in v2.0 as further specifications.
 */
public final class VehicleSpecifications {

    private VehicleSpecifications() {
    }

    /**
     * Case-insensitive partial match on the number as entered, or on the normalized key so that
     * "dhk1234" also finds "DHK-1234".
     */
    public static Specification<Vehicle> vehicleNumberContains(String search) {
        if (search == null || search.isBlank()) {
            return (root, query, cb) -> null;
        }
        String term = search.trim();
        String normalizedTerm = VehicleNumber.normalize(term);
        return (root, query, cb) -> {
            var asEntered = cb.like(cb.lower(root.get("vehicleNumber")),
                    LikePatterns.containsIgnoreCase(term), LikePatterns.ESCAPE_CHAR);
            if (normalizedTerm.isEmpty()) {
                return asEntered;
            }
            var normalized = cb.like(cb.lower(root.get("numberKey")),
                    LikePatterns.containsIgnoreCase(normalizedTerm), LikePatterns.ESCAPE_CHAR);
            return cb.or(asEntered, normalized);
        };
    }
}
