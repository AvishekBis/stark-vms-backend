package com.starkgroup.vms.vehicle;

import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.DuplicateResourceException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.vehicle.dto.UpdateVehicleRequest;
import com.starkgroup.vms.vehicle.dto.VehicleRequest;
import com.starkgroup.vms.vehicle.dto.VehicleResponse;

/**
 * Vehicle business rules: registration, edit, delete and the duplicate-number rule
 * (FR-V01..FR-V05, BR-01, BR-02) and optimistic locking on edit (ADR 0023).
 */
@Service
public class VehicleService {

    private static final String RESOURCE_NAME = "Vehicle";
    private static final String RECORD_NOUN = "vehicle";
    static final String VEHICLE_NUMBER_FIELD = "vehicleNumber";

    private final VehicleRepository vehicleRepository;
    private final VehicleMapper mapper;

    public VehicleService(VehicleRepository vehicleRepository, VehicleMapper mapper) {
        this.vehicleRepository = vehicleRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<VehicleResponse> findAll(String search, int page, int size, String sort) {
        PageRequest pageRequest = PageRequests.of(page, size, sort, VehicleSortField.apiToProperty(),
                VehicleSortField.DEFAULT_SORT);
        PageRequest stablePageRequest = pageRequest.withSort(pageRequest.getSort().and(VehicleSortField.TIE_BREAKER));
        Specification<Vehicle> specification = VehicleSpecifications.vehicleNumberContains(search);
        return PageResponse.from(vehicleRepository.findAll(specification, stablePageRequest).map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    public VehicleResponse findById(Long id) {
        return mapper.toResponse(getVehicle(id));
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
     * (ADR 0023). If another save commits between this check and the flush, Hibernate's versioned UPDATE matches no
     * row and the ObjectOptimisticLockingFailureException becomes the same 409 (GlobalExceptionHandler). A PUT that
     * changes no value writes nothing, so the version stays.
     */
    @Transactional
    public VehicleResponse update(Long id, UpdateVehicleRequest request) {
        Vehicle vehicle = getVehicle(id);
        if (!Objects.equals(vehicle.getVersion(), request.version())) {
            throw new StaleVersionException(RECORD_NOUN);
        }
        String numberKey = VehicleNumber.normalize(request.vehicleNumber());
        if (vehicleRepository.existsByNumberKeyAndIdNot(numberKey, id)) {
            throw duplicateNumber(request.vehicleNumber());
        }
        mapper.updateEntity(vehicle, request, numberKey);
        return mapper.toResponse(saveAndFlush(vehicle, request.vehicleNumber()));
    }

    /**
     * Hard delete. BR-10 (block delete when history exists) is introduced in v2.0 together with
     * documents, maintenance and assignments; in v1.0 a vehicle has no history yet.
     */
    @Transactional
    public void delete(Long id) {
        Vehicle vehicle = getVehicle(id);
        vehicleRepository.delete(vehicle);
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
