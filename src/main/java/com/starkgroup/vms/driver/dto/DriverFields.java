package com.starkgroup.vms.driver.dto;

import java.time.LocalDate;

import com.starkgroup.vms.driver.DriverStatus;

/**
 * Fields shared by the create and update requests ({@link DriverRequest}, {@link UpdateDriverRequest}), so the
 * mapper and service treat both alike.
 */
public interface DriverFields {

    String fullName();

    String phone();

    String licenseNumber();

    LocalDate licenseExpiry();

    DriverStatus status();

    default DriverStatus statusOrDefault() {
        return status() == null ? DriverStatus.ACTIVE : status();
    }
}
