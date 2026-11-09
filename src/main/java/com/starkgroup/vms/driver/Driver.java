package com.starkgroup.vms.driver;

import java.time.LocalDate;

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
 * A driver (FR-DR01). {@code phone} is stored normalized; {@code licenseNumber} keeps the value as entered
 * (trimmed) and {@code licenseKey} is its normalized form. Both phone and licence key are UNIQUE.
 */
@Entity
@Table(name = "drivers")
public class Driver extends AuditableEntity {

    public static final String PHONE_UNIQUE_CONSTRAINT = "uk_drivers_phone";
    public static final String LICENSE_KEY_UNIQUE_CONSTRAINT = "uk_drivers_license_key";
    public static final String DUPLICATE_PHONE_TITLE = "Duplicate phone number";
    public static final String DUPLICATE_LICENSE_TITLE = "Duplicate licence number";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "license_number", nullable = false, length = 40)
    private String licenseNumber;

    @Column(name = "license_key", nullable = false, length = 40)
    private String licenseKey;

    @Column(name = "license_expiry", nullable = false)
    private LocalDate licenseExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DriverStatus status = DriverStatus.ACTIVE;

    /** Optimistic locking (ADR 0023): set and incremented by Hibernate only. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Driver() {
    }

    public Driver(String fullName, String phone, String licenseNumber, String licenseKey, LocalDate licenseExpiry) {
        this.fullName = fullName;
        this.phone = phone;
        this.licenseNumber = licenseNumber;
        this.licenseKey = licenseKey;
        this.licenseExpiry = licenseExpiry;
    }

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public void changeLicense(String licenseNumber, String licenseKey) {
        this.licenseNumber = licenseNumber;
        this.licenseKey = licenseKey;
    }

    public LocalDate getLicenseExpiry() {
        return licenseExpiry;
    }

    public void setLicenseExpiry(LocalDate licenseExpiry) {
        this.licenseExpiry = licenseExpiry;
    }

    public DriverStatus getStatus() {
        return status;
    }

    public void setStatus(DriverStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == DriverStatus.ACTIVE;
    }
}
