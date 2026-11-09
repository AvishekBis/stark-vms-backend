package com.starkgroup.vms.driver;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.expiry.ExpiryInfo;
import com.starkgroup.vms.driver.dto.DriverFields;
import com.starkgroup.vms.driver.dto.DriverResponse;
import com.starkgroup.vms.vehicle.Vehicle;

/**
 * Entity to DTO mapping; the licence status is computed here from the expiry date (BR-08), never stored.
 */
@Component
public class DriverMapper {

    private final ExpiryCalculator expiryCalculator;

    public DriverMapper(ExpiryCalculator expiryCalculator) {
        this.expiryCalculator = expiryCalculator;
    }

    public Driver toEntity(DriverFields request, String phone, String licenseKey) {
        Driver driver = new Driver(request.fullName().trim(), phone, request.licenseNumber().trim(), licenseKey,
                request.licenseExpiry());
        driver.setStatus(request.statusOrDefault());
        return driver;
    }

    public void updateEntity(Driver driver, DriverFields request, String phone, String licenseKey) {
        driver.setFullName(request.fullName().trim());
        driver.setPhone(phone);
        driver.changeLicense(request.licenseNumber().trim(), licenseKey);
        driver.setLicenseExpiry(request.licenseExpiry());
        driver.setStatus(request.statusOrDefault());
    }

    /**
     * @param currentVehicle the driver's current vehicle, or {@code null}
     */
    public DriverResponse toResponse(Driver driver, Vehicle currentVehicle) {
        ExpiryInfo licence = expiryCalculator.evaluate(driver.getLicenseExpiry());
        return new DriverResponse(
                driver.getId(),
                driver.getFullName(),
                driver.getPhone(),
                driver.getLicenseNumber(),
                driver.getLicenseExpiry(),
                licence.status(),
                licence.daysUntilExpiry(),
                driver.getStatus(),
                currentVehicle == null ? null
                        : new DriverResponse.CurrentVehicle(currentVehicle.getId(), currentVehicle.getVehicleNumber()),
                driver.getCreatedAt(),
                driver.getUpdatedAt(),
                driver.getCreatedBy(),
                driver.getVersion());
    }
}
