package com.starkgroup.vms.driver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.UniqueConstraintRule;

/**
 * FR-DR01 safety net: violations of {@code uk_drivers_phone} / {@code uk_drivers_license_key} anywhere in the
 * application are reported as the same 409s as the checks in {@link DriverService}.
 */
@Configuration
public class DriverConstraintConfig {

    @Bean
    public UniqueConstraintRule driverPhoneUniqueRule() {
        return new UniqueConstraintRule(Driver.PHONE_UNIQUE_CONSTRAINT, ApiErrorType.DUPLICATE_DRIVER_PHONE,
                Driver.DUPLICATE_PHONE_TITLE, DriverService.PHONE_FIELD,
                "This phone number is already registered to another driver.");
    }

    @Bean
    public UniqueConstraintRule driverLicenseUniqueRule() {
        return new UniqueConstraintRule(Driver.LICENSE_KEY_UNIQUE_CONSTRAINT, ApiErrorType.DUPLICATE_DRIVER_LICENSE,
                Driver.DUPLICATE_LICENSE_TITLE, DriverService.LICENSE_NUMBER_FIELD,
                "This licence number is already registered to another driver.");
    }
}
