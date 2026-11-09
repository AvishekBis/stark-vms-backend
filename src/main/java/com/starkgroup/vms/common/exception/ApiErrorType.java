package com.starkgroup.vms.common.exception;

import java.net.URI;

import org.springframework.http.HttpStatusCode;

/**
 * Problem Details {@code type} values shared by every error response (API contract sections 3 and 6.3).
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

    // v1.1 (contract section 6.3)
    public static final URI TOO_MANY_LOGIN_ATTEMPTS = URI.create(BASE + "too-many-login-attempts");
    public static final URI STALE_VERSION = URI.create(BASE + "stale-version");

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
            default -> status.is5xxServerError() ? INTERNAL : BAD_REQUEST;
        };
    }
}
