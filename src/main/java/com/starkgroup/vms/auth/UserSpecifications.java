package com.starkgroup.vms.auth;

import org.springframework.data.jpa.domain.Specification;

import com.starkgroup.vms.common.util.LikePatterns;

/**
 * Composable, parameter-bound criteria for the user list (contract 7.3.2). User input is always bound as a
 * parameter and LIKE wildcards in it are escaped.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /** Case-insensitive partial match on e-mail or full name. */
    public static Specification<User> search(String search) {
        if (search == null || search.isBlank()) {
            return (root, query, cb) -> null;
        }
        String pattern = LikePatterns.containsIgnoreCase(search.trim());
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), pattern, LikePatterns.ESCAPE_CHAR),
                cb.like(cb.lower(root.get("fullName")), pattern, LikePatterns.ESCAPE_CHAR));
    }

    /** {@code null} means no restriction. */
    public static Specification<User> hasRole(Role role) {
        if (role == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    /** {@code null} means no restriction. */
    public static Specification<User> isEnabled(Boolean enabled) {
        if (enabled == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("enabled"), enabled);
    }
}
