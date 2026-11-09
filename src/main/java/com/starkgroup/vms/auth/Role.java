package com.starkgroup.vms.auth;

/**
 * Application roles (guideline section 4). Spring authorities are "ROLE_" + name.
 */
public enum Role {
    ADMIN,
    STAFF;

    public String authority() {
        return "ROLE_" + name();
    }
}
