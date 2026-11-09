package com.starkgroup.vms.vehicle;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.driver.Driver;
import com.starkgroup.vms.vehicle.dto.VehicleRequest;
import com.starkgroup.vms.vehicle.dto.VehicleResponse;

/**
 * Entity to DTO mapping. Optional text fields are trimmed and blank values stored as null.
 */
@Component
public class VehicleMapper {

    public Vehicle toEntity(VehicleRequest request, String numberKey) {
        Vehicle vehicle = new Vehicle(request.vehicleNumber().trim(), numberKey, request.vehicleType());
        copyDetails(request, vehicle);
        return vehicle;
    }

    public void updateEntity(Vehicle vehicle, VehicleRequest request, String numberKey) {
        vehicle.changeNumber(request.vehicleNumber().trim(), numberKey);
        vehicle.setVehicleType(request.vehicleType());
        copyDetails(request, vehicle);
    }

    /** Response without a current driver (e.g. a vehicle that was just registered). */
    public VehicleResponse toResponse(Vehicle vehicle) {
        return toResponse(vehicle, null);
    }

    /**
     * @param currentDriver the vehicle's current driver, or {@code null}
     */
    public VehicleResponse toResponse(Vehicle vehicle, Driver currentDriver) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getVehicleNumber(),
                vehicle.getVehicleType(),
                vehicle.getBrand(),
                vehicle.getModel(),
                vehicle.getManufactureYear() == null ? null : vehicle.getManufactureYear().intValue(),
                vehicle.getColor(),
                vehicle.getFuelType(),
                vehicle.getStatus(),
                vehicle.getCreatedAt(),
                vehicle.getUpdatedAt(),
                vehicle.getCreatedBy(),
                currentDriver == null ? null : new VehicleResponse.CurrentDriver(currentDriver.getId(),
                        currentDriver.getFullName(), currentDriver.getPhone()),
                vehicle.getVersion());
    }

    private static void copyDetails(VehicleRequest request, Vehicle vehicle) {
        vehicle.setBrand(trimToNull(request.brand()));
        vehicle.setModel(trimToNull(request.model()));
        vehicle.setManufactureYear(request.manufactureYear() == null ? null : request.manufactureYear().shortValue());
        vehicle.setColor(trimToNull(request.color()));
        vehicle.setFuelType(request.fuelType());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
