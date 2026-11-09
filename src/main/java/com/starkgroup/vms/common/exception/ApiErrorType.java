package com.starkgroup.vms.common.exception;

import java.net.URI;

import org.springframework.http.HttpStatusCode;

/**
 * Problem Details {@code type} values shared by every error response (API contract sections 3, 6.8 and 7.5).
 */
public final class ApiErrorType {

    private static final String BASE = "https://stark-vms/errors/";

    public static final URI VALIDATION = URI.create(BASE + "validation");
    public static final URI DUPLICATE_VEHICLE_NUMBER = URI.create(BASE + "duplicate-vehicle-number");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI UNAUTHORIZED = URI.create(BASE + "unauthorized");
    public static final URI FORBIDDEN = URI.create(BASE + "forbidden");
    public static final URI BAD_REQUEST = URI.create(BASE + "bad-request");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI INTERNAL = URI.create(BASE + "internal");

    // v2.0 (contract section 6.8)
    public static final URI VEHICLE_HAS_HISTORY = URI.create(BASE + "vehicle-has-history");
    public static final URI DRIVER_HAS_HISTORY = URI.create(BASE + "driver-has-history");
    public static final URI DUPLICATE_DRIVER_PHONE = URI.create(BASE + "duplicate-driver-phone");
    public static final URI DUPLICATE_DRIVER_LICENSE = URI.create(BASE + "duplicate-driver-license");
    public static final URI ASSIGNMENT_NOT_ALLOWED = URI.create(BASE + "assignment-not-allowed");
    public static final URI DRIVER_ALREADY_ASSIGNED = URI.create(BASE + "driver-already-assigned");
    public static final URI NO_CURRENT_DRIVER = URI.create(BASE + "no-current-driver");
    public static final URI FILE_TOO_LARGE = URI.create(BASE + "file-too-large");

    // v2.1 (contract section 7.5)
    public static final URI TOO_MANY_LOGIN_ATTEMPTS = URI.create(BASE + "too-many-login-attempts");
    public static final URI STALE_VERSION = URI.create(BASE + "stale-version");
    public static final URI DUPLICATE_EMAIL = URI.create(BASE + "duplicate-email");
    public static final URI SELF_MODIFICATION = URI.create(BASE + "self-modification");
    public static final URI LAST_ADMIN = URI.create(BASE + "last-admin");

    private ApiErrorType() {
    }

    /**
     * Default type for an HTTP status when no more specific type applies.
     */
    public static URI forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            case 413 -> FILE_TOO_LARGE;
            default -> status.is5xxServerError() ? INTERNAL : BAD_REQUEST;
        };
    }
}
