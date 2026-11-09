package com.starkgroup.vms.vehicle;

import com.starkgroup.vms.common.persistence.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A fleet vehicle. {@code vehicleNumber} keeps the number as entered (trimmed); {@code numberKey}
 * is its normalized form and carries the UNIQUE constraint (BR-01).
 */
@Entity
@Table(name = "vehicles")
public class Vehicle extends AuditableEntity {

    public static final String NUMBER_KEY_UNIQUE_CONSTRAINT = "uk_vehicles_number_key";
    public static final String DUPLICATE_NUMBER_TITLE = "Duplicate vehicle number";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_number", nullable = false, length = 40)
    private String vehicleNumber;

    @Column(name = "number_key", nullable = false, length = 40)
    private String numberKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 30)
    private VehicleType vehicleType;

    @Column(name = "brand", length = 50)
    private String brand;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "manufacture_year")
    private Short manufactureYear;

    @Column(name = "color", length = 30)
    private String color;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type", length = 20)
    private FuelType fuelType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VehicleStatus status = VehicleStatus.ACTIVE;

    /** Optimistic locking (ADR 0023): set and incremented by Hibernate only. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Vehicle() {
    }

    public Vehicle(String vehicleNumber, String numberKey, VehicleType vehicleType) {
        this.vehicleNumber = vehicleNumber;
        this.numberKey = numberKey;
        this.vehicleType = vehicleType;
    }

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public String getNumberKey() {
        return numberKey;
    }

    public void changeNumber(String vehicleNumber, String numberKey) {
        this.vehicleNumber = vehicleNumber;
        this.numberKey = numberKey;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(VehicleType vehicleType) {
        this.vehicleType = vehicleType;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Short getManufactureYear() {
        return manufactureYear;
    }

    public void setManufactureYear(Short manufactureYear) {
        this.manufactureYear = manufactureYear;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public FuelType getFuelType() {
        return fuelType;
    }

    public void setFuelType(FuelType fuelType) {
        this.fuelType = fuelType;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }
}
