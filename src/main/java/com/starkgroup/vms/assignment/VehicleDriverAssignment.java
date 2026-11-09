package com.starkgroup.vms.assignment;

import java.time.Instant;

import com.starkgroup.vms.common.persistence.AuditableEntity;
import com.starkgroup.vms.driver.Driver;
import com.starkgroup.vms.vehicle.Vehicle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One period in which a driver drove a vehicle (FR-DR02). {@code unassignedAt == null} marks the current
 * assignment; partial unique indexes allow at most one current row per vehicle and per driver (BR-06).
 * Rows are closed, never deleted or overwritten, so the history is kept. {@code createdBy} is the user who
 * made the assignment ({@code assignedBy} in the API).
 */
@Entity
@Table(name = "vehicle_driver_assignments")
public class VehicleDriverAssignment extends AuditableEntity {

    public static final String OPEN_VEHICLE_UNIQUE_INDEX = "uq_assignment_open_vehicle";
    public static final String OPEN_DRIVER_UNIQUE_INDEX = "uq_assignment_open_driver";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false, updatable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false, updatable = false)
    private Driver driver;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "unassigned_at")
    private Instant unassignedAt;

    protected VehicleDriverAssignment() {
    }

    public VehicleDriverAssignment(Vehicle vehicle, Driver driver, Instant assignedAt) {
        this.vehicle = vehicle;
        this.driver = driver;
        this.assignedAt = assignedAt;
    }

    public Long getId() {
        return id;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public Driver getDriver() {
        return driver;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getUnassignedAt() {
        return unassignedAt;
    }

    public boolean isCurrent() {
        return unassignedAt == null;
    }

    public void close(Instant at) {
        if (unassignedAt == null) {
            this.unassignedAt = at;
        }
    }
}
