package com.starkgroup.vms.driver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.starkgroup.vms.assignment.VehicleDriverAssignment;
import com.starkgroup.vms.common.util.LikePatterns;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;

/**
 * Composable, parameter-bound query criteria for the driver list (contract 6.3). No SQL strings.
 */
public final class DriverSpecifications {

    private DriverSpecifications() {
    }

    /**
     * Case-insensitive partial match on full name, phone (search term normalized like a phone number) or
     * licence number (as entered or normalized).
     */
    public static Specification<Driver> search(String search) {
        if (search == null || search.isBlank()) {
            return (root, query, cb) -> null;
        }
        String term = search.trim();
        String phoneTerm = DriverPhone.normalize(term);
        String licenseTerm = LicenseNumber.normalize(term);
        return (root, query, cb) -> {
            List<Predicate> matches = new ArrayList<>();
            matches.add(cb.like(cb.lower(root.get("fullName")), LikePatterns.containsIgnoreCase(term),
                    LikePatterns.ESCAPE_CHAR));
            matches.add(cb.like(cb.lower(root.get("licenseNumber")), LikePatterns.containsIgnoreCase(term),
                    LikePatterns.ESCAPE_CHAR));
            if (!phoneTerm.isEmpty()) {
                matches.add(cb.like(root.get("phone"), LikePatterns.containsIgnoreCase(phoneTerm),
                        LikePatterns.ESCAPE_CHAR));
            }
            if (!licenseTerm.isEmpty()) {
                matches.add(cb.like(cb.lower(root.get("licenseKey")), LikePatterns.containsIgnoreCase(licenseTerm),
                        LikePatterns.ESCAPE_CHAR));
            }
            return cb.or(matches.toArray(Predicate[]::new));
        };
    }

    public static Specification<Driver> hasStatus(DriverStatus status) {
        if (status == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * {@code available=true}: ACTIVE drivers without a current vehicle (the "assign driver" dialog).
     * {@code false} or {@code null}: no restriction.
     */
    public static Specification<Driver> available(Boolean available) {
        if (!Boolean.TRUE.equals(available)) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> {
            Subquery<Long> current = query.subquery(Long.class);
            var assignment = current.from(VehicleDriverAssignment.class);
            current.select(assignment.get("id")).where(
                    cb.equal(assignment.get("driver"), root),
                    cb.isNull(assignment.get("unassignedAt")));
            return cb.and(cb.equal(root.get("status"), DriverStatus.ACTIVE), cb.not(cb.exists(current)));
        };
    }
}
