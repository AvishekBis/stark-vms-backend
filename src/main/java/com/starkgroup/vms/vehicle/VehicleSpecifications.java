package com.starkgroup.vms.vehicle;

import org.springframework.data.jpa.domain.Specification;

import com.starkgroup.vms.assignment.VehicleDriverAssignment;
import com.starkgroup.vms.common.util.LikePatterns;

import jakarta.persistence.criteria.Subquery;

/**
 * Composable, parameter-bound query criteria for the vehicle list (FR-V08, FR-V09). No SQL strings - user
 * input is always bound as a parameter.
 */
public final class VehicleSpecifications {

    private VehicleSpecifications() {
    }

    /**
     * FR-V08: case-insensitive partial match on the vehicle number (as entered or normalized), brand, model or
     * the full name of the current driver.
     */
    public static Specification<Vehicle> search(String search) {
        if (search == null || search.isBlank()) {
            return (root, query, cb) -> null;
        }
        String pattern = LikePatterns.containsIgnoreCase(search.trim());
        Specification<Vehicle> details = (root, query, cb) -> {
            Subquery<Long> currentDriver = query.subquery(Long.class);
            var assignment = currentDriver.from(VehicleDriverAssignment.class);
            currentDriver.select(assignment.get("id")).where(
                    cb.equal(assignment.get("vehicle"), root),
                    cb.isNull(assignment.get("unassignedAt")),
                    cb.like(cb.lower(assignment.get("driver").get("fullName")), pattern, LikePatterns.ESCAPE_CHAR));
            return cb.or(
                    cb.like(cb.lower(root.get("brand")), pattern, LikePatterns.ESCAPE_CHAR),
                    cb.like(cb.lower(root.get("model")), pattern, LikePatterns.ESCAPE_CHAR),
                    cb.exists(currentDriver));
        };
        return vehicleNumberContains(search).or(details);
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

    /** FR-V09 status filter; {@code null} means no restriction. */
    public static Specification<Vehicle> hasStatus(VehicleStatus status) {
        if (status == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** FR-V09 type filter; {@code null} means no restriction. */
    public static Specification<Vehicle> hasType(VehicleType type) {
        if (type == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("vehicleType"), type);
    }
}
