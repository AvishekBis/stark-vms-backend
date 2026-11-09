package com.starkgroup.vms.vehicle.dto;

import com.starkgroup.vms.vehicle.FuelType;
import com.starkgroup.vms.vehicle.VehicleType;

/**
 * Fields shared by create and update requests, so the mapper and service treat both alike.
 */
public interface VehicleRequest {

    /** BR-03: at least one letter or digit; an all-blank value is reported by @NotBlank only. */
    String VEHICLE_NUMBER_PATTERN = "(?s)\\s*|.*[\\p{L}\\p{N}].*";

    String vehicleNumber();

    VehicleType vehicleType();

    String brand();

    String model();

    Integer manufactureYear();

    String color();

    FuelType fuelType();
}
