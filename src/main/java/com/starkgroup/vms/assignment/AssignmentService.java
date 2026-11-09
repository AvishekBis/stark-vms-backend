package com.starkgroup.vms.assignment;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.assignment.dto.AssignmentResponse;
import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.driver.Driver;
import com.starkgroup.vms.driver.DriverRepository;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleRepository;
import com.starkgroup.vms.vehicle.VehicleStatus;

/**
 * Driver-vehicle assignment rules (FR-DR02, BR-05, BR-06, contract 6.4). A vehicle has at most one current
 * driver and a driver at most one current vehicle; changing the driver closes the previous assignment in the
 * same transaction and keeps it as history.
 */
@Service
public class AssignmentService {

    static final String DRIVER_ID_FIELD = "driverId";
    static final String ASSIGNMENT_NOT_ALLOWED_TITLE = "Assignment not allowed";
    static final String DRIVER_ALREADY_ASSIGNED_TITLE = "Driver already assigned";
    static final String NO_CURRENT_DRIVER_TITLE = "No current driver";
    static final String CONCURRENT_ASSIGNMENT_MESSAGE =
            "The vehicle or the driver was assigned by someone else in the meantime. Reload and try again.";

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("assignedAt"), Sort.Order.desc("id"));

    private final VehicleDriverAssignmentRepository assignmentRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final AssignmentMapper mapper;
    private final Clock clock;

    public AssignmentService(VehicleDriverAssignmentRepository assignmentRepository,
                             VehicleRepository vehicleRepository,
                             DriverRepository driverRepository,
                             AssignmentMapper mapper,
                             Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Assigns {@code driverId} as the current driver of the vehicle (rules 1-5 of contract 6.4, one transaction).
     */
    @Transactional
    public AssignmentResponse assign(Long vehicleId, Long driverId) {
        // Row locks (vehicle first, then driver) serialize this with a concurrent status change of either one and
        // with other assignments of the same vehicle or driver; the partial unique indexes stay the safety net.
        Vehicle vehicle = vehicleRepository.findForUpdateById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Vehicle", vehicleId));
        Driver driver = driverRepository.findForUpdateById(driverId)
                .orElseThrow(() -> new NotFoundException("Driver", driverId));
        requireAssignable(vehicle, driver);

        // Rule 2: same driver again is idempotent.
        Optional<VehicleDriverAssignment> vehicleCurrent =
                assignmentRepository.findByVehicleIdAndUnassignedAtIsNull(vehicleId);
        if (vehicleCurrent.isPresent() && vehicleCurrent.get().getDriver().getId().equals(driverId)) {
            return mapper.toResponse(vehicleCurrent.get());
        }

        requireDriverFree(driverId, driver);

        // Rule 4: close the vehicle's open assignment first, then open the new one with the same timestamp.
        Instant now = now();
        vehicleCurrent.ifPresent(previous -> previous.close(now));
        return mapper.toResponse(openAssignment(vehicle, driver, now));
    }

    /** Rule 1 (BR-05): only ACTIVE vehicles and ACTIVE drivers. */
    private static void requireAssignable(Vehicle vehicle, Driver driver) {
        if (vehicle.getStatus() != VehicleStatus.ACTIVE) {
            throw notAllowed("Vehicle " + vehicle.getVehicleNumber() + " is " + vehicle.getStatus()
                    + "; only ACTIVE vehicles can be assigned a driver.");
        }
        if (!driver.isActive()) {
            throw notAllowed("Driver " + driver.getFullName() + " is " + driver.getStatus()
                    + "; only ACTIVE drivers can be assigned to a vehicle.");
        }
    }

    /** Rule 3 (BR-06, TC-10): the driver must not drive another vehicle. */
    private void requireDriverFree(Long driverId, Driver driver) {
        assignmentRepository.findByDriverIdAndUnassignedAtIsNull(driverId).ifPresent(other -> {
            throw driverAlreadyAssigned("Driver " + driver.getFullName() + " is already assigned to vehicle "
                    + other.getVehicle().getVehicleNumber() + ". Unassign them first.");
        });
    }

    /**
     * Flushes the closed previous assignment so the partial unique index is free, then saves the new one. Rule 5:
     * a concurrent assignment that won the race on a partial unique index is the same 409.
     */
    private VehicleDriverAssignment openAssignment(Vehicle vehicle, Driver driver, Instant now) {
        try {
            assignmentRepository.flush();
            return assignmentRepository.saveAndFlush(new VehicleDriverAssignment(vehicle, driver, now));
        } catch (DataIntegrityViolationException ex) {
            if (isOpenAssignmentViolation(ex)) {
                throw driverAlreadyAssigned(CONCURRENT_ASSIGNMENT_MESSAGE);
            }
            throw ex;
        }
    }

    /**
     * Closes the vehicle's current assignment (DELETE /vehicles/{id}/driver).
     */
    @Transactional
    public void unassign(Long vehicleId) {
        // Same row lock as assign/changeStatus: a concurrent re-assignment of this vehicle cannot interleave with
        // the unassign (otherwise the unassign could re-close an already closed row with a later timestamp).
        Vehicle vehicle = vehicleRepository.findForUpdateById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Vehicle", vehicleId));
        VehicleDriverAssignment current = assignmentRepository.findByVehicleIdAndUnassignedAtIsNull(vehicleId)
                .orElseThrow(() -> new BusinessRuleException(ApiErrorType.NO_CURRENT_DRIVER, NO_CURRENT_DRIVER_TITLE,
                        "Vehicle " + vehicle.getVehicleNumber() + " has no current driver."));
        current.close(now());
        assignmentRepository.flush();
    }

    /**
     * Closes the vehicle's current assignment if there is one - used when a vehicle becomes INACTIVE.
     * Joins the caller's transaction.
     */
    @Transactional
    public void closeCurrentForVehicle(Long vehicleId) {
        assignmentRepository.findByVehicleIdAndUnassignedAtIsNull(vehicleId).ifPresent(this::closeAndFlush);
    }

    /**
     * Closes the driver's current assignment if there is one - used when a driver becomes INACTIVE.
     * Joins the caller's transaction.
     */
    @Transactional
    public void closeCurrentForDriver(Long driverId) {
        assignmentRepository.findByDriverIdAndUnassignedAtIsNull(driverId).ifPresent(this::closeAndFlush);
    }

    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> historyForVehicle(Long vehicleId, int page, int size) {
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new NotFoundException("Vehicle", vehicleId);
        }
        return PageResponse.from(assignmentRepository.findByVehicleId(vehicleId, historyPage(page, size))
                .map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> historyForDriver(Long driverId, int page, int size) {
        if (!driverRepository.existsById(driverId)) {
            throw new NotFoundException("Driver", driverId);
        }
        return PageResponse.from(assignmentRepository.findByDriverId(driverId, historyPage(page, size))
                .map(mapper::toResponse));
    }

    /** Current driver per vehicle id, for a whole page of vehicles in one query. */
    @Transactional(readOnly = true)
    public Map<Long, Driver> currentDriversByVehicleId(Collection<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        return assignmentRepository.findCurrentWithDriverByVehicleIds(vehicleIds).stream()
                .collect(Collectors.toMap(a -> a.getVehicle().getId(), VehicleDriverAssignment::getDriver));
    }

    /** Current vehicle per driver id, for a whole page of drivers in one query. */
    @Transactional(readOnly = true)
    public Map<Long, Vehicle> currentVehiclesByDriverId(Collection<Long> driverIds) {
        if (driverIds.isEmpty()) {
            return Map.of();
        }
        return assignmentRepository.findCurrentWithVehicleByDriverIds(driverIds).stream()
                .collect(Collectors.toMap(a -> a.getDriver().getId(), VehicleDriverAssignment::getVehicle,
                        (first, second) -> first));
    }

    @Transactional(readOnly = true)
    public Optional<Driver> currentDriverOf(Long vehicleId) {
        return assignmentRepository.findByVehicleIdAndUnassignedAtIsNull(vehicleId)
                .map(VehicleDriverAssignment::getDriver);
    }

    @Transactional(readOnly = true)
    public Optional<Vehicle> currentVehicleOf(Long driverId) {
        return assignmentRepository.findByDriverIdAndUnassignedAtIsNull(driverId)
                .map(VehicleDriverAssignment::getVehicle);
    }

    @Transactional(readOnly = true)
    public boolean driverHasHistory(Long driverId) {
        return assignmentRepository.existsByDriverId(driverId);
    }

    private void closeAndFlush(VehicleDriverAssignment assignment) {
        assignment.close(now());
        assignmentRepository.flush();
    }

    private static PageRequest historyPage(int page, int size) {
        return PageRequests.of(page, size, null, Map.of(), NEWEST_FIRST);
    }

    /** Truncated to PostgreSQL TIMESTAMPTZ precision, like the audit timestamps. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessRuleException notAllowed(String message) {
        return new BusinessRuleException(ApiErrorType.ASSIGNMENT_NOT_ALLOWED, ASSIGNMENT_NOT_ALLOWED_TITLE, message);
    }

    private static BusinessRuleException driverAlreadyAssigned(String message) {
        return new BusinessRuleException(ApiErrorType.DRIVER_ALREADY_ASSIGNED, DRIVER_ALREADY_ASSIGNED_TITLE,
                DRIVER_ID_FIELD, message);
    }

    private static boolean isOpenAssignmentViolation(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        return message != null && (message.contains(VehicleDriverAssignment.OPEN_VEHICLE_UNIQUE_INDEX)
                || message.contains(VehicleDriverAssignment.OPEN_DRIVER_UNIQUE_INDEX));
    }
}
