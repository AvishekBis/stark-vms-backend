package com.starkgroup.vms.maintenance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.exception.FieldValidationException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.maintenance.dto.MaintenanceFields;
import com.starkgroup.vms.maintenance.dto.MaintenanceHistoryResponse;
import com.starkgroup.vms.maintenance.dto.MaintenanceRequest;
import com.starkgroup.vms.maintenance.dto.MaintenanceResponse;
import com.starkgroup.vms.maintenance.dto.UpdateMaintenanceRequest;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleRepository;

/**
 * Maintenance records and per-vehicle history (FR-M01, FR-M02, BR-09).
 */
@Service
public class MaintenanceService {

    private static final String RESOURCE_NAME = "Maintenance record";
    private static final String RECORD_NOUN = "maintenance record";
    static final String SERVICE_DATE_FIELD = "serviceDate";
    static final String COST_FIELD = "cost";
    static final String FUTURE_SERVICE_DATE = "Service date cannot be in the future.";
    static final String INVALID_COST = "Cost must be 0 or more with at most 2 decimals.";
    private static final BigDecimal MAX_COST = new BigDecimal("9999999999.99");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("serviceDate"), Sort.Order.desc("id"));

    private final MaintenanceRecordRepository maintenanceRepository;
    private final VehicleRepository vehicleRepository;
    private final MaintenanceMapper mapper;
    private final ExpiryCalculator dates;

    public MaintenanceService(MaintenanceRecordRepository maintenanceRepository, VehicleRepository vehicleRepository,
                              MaintenanceMapper mapper, ExpiryCalculator dates) {
        this.maintenanceRepository = maintenanceRepository;
        this.vehicleRepository = vehicleRepository;
        this.mapper = mapper;
        this.dates = dates;
    }

    /**
     * FR-M02: newest first (service date desc, then id desc), with the total cost over all records of the
     * vehicle from one SUM query.
     */
    @Transactional(readOnly = true)
    public MaintenanceHistoryResponse history(Long vehicleId, int page, int size) {
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new NotFoundException("Vehicle", vehicleId);
        }
        PageRequest pageRequest = PageRequests.of(page, size, null, Map.of(), NEWEST_FIRST);
        PageResponse<MaintenanceResponse> records = PageResponse.from(
                maintenanceRepository.findByVehicleId(vehicleId, pageRequest).map(mapper::toResponse));
        return MaintenanceHistoryResponse.of(records, maintenanceRepository.sumCostByVehicleId(vehicleId));
    }

    @Transactional(readOnly = true)
    public MaintenanceResponse findById(Long recordId) {
        return mapper.toResponse(getRecord(recordId));
    }

    @Transactional
    public MaintenanceResponse create(Long vehicleId, MaintenanceRequest request) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Vehicle", vehicleId));
        validate(request);
        MaintenanceRecord record = new MaintenanceRecord(vehicle);
        mapper.copy(request, record);
        return mapper.toResponse(maintenanceRepository.saveAndFlush(record));
    }

    /**
     * A {@code version} other than the stored one is refused with 409 stale-version (ADR 0023). There is no row lock
     * here: a concurrent commit between this check and the flush makes the versioned UPDATE match no row, which ends
     * in the same 409 (ObjectOptimisticLockingFailureException, mapped in GlobalExceptionHandler).
     */
    @Transactional
    public MaintenanceResponse update(Long recordId, UpdateMaintenanceRequest request) {
        MaintenanceRecord record = getRecord(recordId);
        if (!Objects.equals(record.getVersion(), request.version())) {
            throw new StaleVersionException(RECORD_NOUN);
        }
        validate(request);
        mapper.copy(request, record);
        return mapper.toResponse(maintenanceRepository.saveAndFlush(record));
    }

    @Transactional
    public void delete(Long recordId) {
        maintenanceRepository.delete(getRecord(recordId));
    }

    /**
     * BR-09 rules that need "today" or that must hold even if the DTO validation is bypassed.
     */
    private void validate(MaintenanceFields request) {
        LocalDate today = dates.today();
        if (request.serviceDate() != null && request.serviceDate().isAfter(today)) {
            throw new FieldValidationException(SERVICE_DATE_FIELD, FUTURE_SERVICE_DATE);
        }
        BigDecimal cost = request.cost();
        if (cost == null || cost.signum() < 0 || cost.stripTrailingZeros().scale() > 2 || cost.compareTo(MAX_COST) > 0) {
            throw new FieldValidationException(COST_FIELD, INVALID_COST);
        }
    }

    private MaintenanceRecord getRecord(Long recordId) {
        return maintenanceRepository.findWithVehicleById(recordId)
                .orElseThrow(() -> new NotFoundException(RESOURCE_NAME, recordId));
    }
}
