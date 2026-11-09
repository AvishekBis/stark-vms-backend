package com.starkgroup.vms.vehicle;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.assignment.AssignmentService;
import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.common.exception.DuplicateResourceException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.driver.Driver;
import com.starkgroup.vms.vehicle.dto.UpdateVehicleRequest;
import com.starkgroup.vms.vehicle.dto.VehicleRequest;
import com.starkgroup.vms.vehicle.dto.VehicleResponse;

/**
 * Vehicle business rules: registration, edit, delete, the duplicate-number rule, status changes, search and
 * filters (FR-V01..FR-V09, BR-01, BR-02, BR-04, BR-10).
 */
@Service
public class VehicleService {

    private static final String RESOURCE_NAME = "Vehicle";
    private static final String RECORD_NOUN = "vehicle";
    static final String VEHICLE_NUMBER_FIELD = "vehicleNumber";
    static final String VEHICLE_HAS_HISTORY_TITLE = "Vehicle has history";

    private final VehicleRepository vehicleRepository;
    private final VehicleMapper mapper;
    private final AssignmentService assignmentService;
    private final List<VehicleUsage> usages;

    public VehicleService(VehicleRepository vehicleRepository, VehicleMapper mapper,
                          AssignmentService assignmentService, List<VehicleUsage> usages) {
        this.vehicleRepository = vehicleRepository;
        this.mapper = mapper;
        this.assignmentService = assignmentService;
        this.usages = List.copyOf(usages);
    }

    /** v1.0 signature: search only, no status/type filter. */
    @Transactional(readOnly = true)
    public PageResponse<VehicleResponse> findAll(String search, int page, int size, String sort) {
        return findAll(search, null, null, page, size, sort);
    }

    /**
     * FR-V08 / FR-V09: search, status and type filters (AND-ed), paging and whitelisted sorting - all on the
     * server. Current drivers of the page are loaded with one extra query (no N+1).
     */
    @Transactional(readOnly = true)
    public PageResponse<VehicleResponse> findAll(String search, VehicleStatus status, VehicleType type, int page,
                                                 int size, String sort) {
        PageRequest pageRequest = PageRequests.of(page, size, sort, VehicleSortField.apiToProperty(),
                VehicleSortField.DEFAULT_SORT);
        PageRequest stablePageRequest = pageRequest.withSort(pageRequest.getSort().and(VehicleSortField.TIE_BREAKER));
        Specification<Vehicle> specification = Specification.allOf(
                VehicleSpecifications.search(search),
                VehicleSpecifications.hasStatus(status),
                VehicleSpecifications.hasType(type));
        Page<Vehicle> vehicles = vehicleRepository.findAll(specification, stablePageRequest);
        Map<Long, Driver> drivers = assignmentService.currentDriversByVehicleId(
                vehicles.getContent().stream().map(Vehicle::getId).toList());
        return PageResponse.from(vehicles.map(vehicle -> mapper.toResponse(vehicle, drivers.get(vehicle.getId()))));
    }

    @Transactional(readOnly = true)
    public VehicleResponse findById(Long id) {
        return toResponse(getVehicle(id));
    }

    @Transactional
    public VehicleResponse create(VehicleRequest request) {
        String numberKey = VehicleNumber.normalize(request.vehicleNumber());
        if (vehicleRepository.existsByNumberKey(numberKey)) {
            throw duplicateNumber(request.vehicleNumber());
        }
        Vehicle vehicle = mapper.toEntity(request, numberKey);
        return mapper.toResponse(saveAndFlush(vehicle, request.vehicleNumber()));
    }

    /**
     * A {@code version} other than the stored one is refused with 409 stale-version before anything changes
     * (ADR 0023); under the row lock that comparison is final.
     */
    @Transactional
    public VehicleResponse update(Long id, UpdateVehicleRequest request) {
        // Row lock, same as changeStatus/assign: the full-row UPDATE cannot write back a stale status (BR-05).
        Vehicle vehicle = vehicleRepository.findForUpdateById(id)
                .orElseThrow(() -> new NotFoundException(RESOURCE_NAME, id));
        if (!Objects.equals(vehicle.getVersion(), request.version())) {
            throw new StaleVersionException(RECORD_NOUN);
        }
        String numberKey = VehicleNumber.normalize(request.vehicleNumber());
        if (vehicleRepository.existsByNumberKeyAndIdNot(numberKey, id)) {
            throw duplicateNumber(request.vehicleNumber());
        }
        mapper.updateEntity(vehicle, request, numberKey);
        return toResponse(saveAndFlush(vehicle, request.vehicleNumber()));
    }

    /**
     * FR-V07. INACTIVE also closes the current driver assignment in the same transaction, because only ACTIVE
     * vehicles may have a driver (BR-05); MAINTENANCE keeps the driver. The same status again changes nothing.
     */
    @Transactional
    public VehicleResponse changeStatus(Long id, VehicleStatus status) {
        // Row lock: a concurrent driver assignment of this vehicle waits, then sees the new status (BR-05).
        Vehicle vehicle = vehicleRepository.findForUpdateById(id)
                .orElseThrow(() -> new NotFoundException(RESOURCE_NAME, id));
        if (vehicle.getStatus() == status) {
            return toResponse(vehicle);
        }
        if (status == VehicleStatus.INACTIVE) {
            assignmentService.closeCurrentForVehicle(id);
        }
        vehicle.setStatus(status);
        return toResponse(vehicleRepository.saveAndFlush(vehicle));
    }

    /**
     * BR-10: hard delete only when the vehicle has no documents, maintenance records or driver assignments;
     * otherwise 409 and the client is told to set the vehicle INACTIVE. Each owning feature answers for its own
     * records through a {@link VehicleUsage}; the ON DELETE RESTRICT foreign keys are the safety net.
     */
    @Transactional
    public void delete(Long id) {
        Vehicle vehicle = getVehicle(id);
        if (usages.stream().anyMatch(usage -> usage.isUsedBy(id))) {
            throw new BusinessRuleException(ApiErrorType.VEHICLE_HAS_HISTORY, VEHICLE_HAS_HISTORY_TITLE,
                    "Vehicle " + vehicle.getVehicleNumber() + " has documents, maintenance or driver history and "
                            + "cannot be deleted. Set its status to INACTIVE instead.");
        }
        vehicleRepository.delete(vehicle);
    }

    private VehicleResponse toResponse(Vehicle vehicle) {
        return mapper.toResponse(vehicle, assignmentService.currentDriverOf(vehicle.getId()).orElse(null));
    }

    private Vehicle getVehicle(Long id) {
        return vehicleRepository.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_NAME, id));
    }

    /**
     * Flushing inside the transaction turns a lost race on the unique number_key constraint into
     * the same 409 as the service-level check, and fills updatedAt before mapping.
     */
    private Vehicle saveAndFlush(Vehicle vehicle, String rawNumber) {
        try {
            return vehicleRepository.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException ex) {
            if (isNumberKeyViolation(ex)) {
                throw duplicateNumber(rawNumber);
            }
            throw ex;
        }
    }

    private static boolean isNumberKeyViolation(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        return message != null && message.contains(Vehicle.NUMBER_KEY_UNIQUE_CONSTRAINT);
    }

    private static DuplicateResourceException duplicateNumber(String rawNumber) {
        return new DuplicateResourceException(ApiErrorType.DUPLICATE_VEHICLE_NUMBER, Vehicle.DUPLICATE_NUMBER_TITLE,
                VEHICLE_NUMBER_FIELD, "Vehicle number " + rawNumber.trim() + " is already registered.");
    }
}
