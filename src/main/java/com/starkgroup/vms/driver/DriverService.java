package com.starkgroup.vms.driver;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.driver.dto.DriverRequest;
import com.starkgroup.vms.driver.dto.DriverResponse;
import com.starkgroup.vms.driver.dto.UpdateDriverRequest;
import com.starkgroup.vms.vehicle.Vehicle;

/**
 * Driver business rules (FR-DR01): unique phone and licence number (service check + DB constraints),
 * INACTIVE closes the current assignment, and drivers with assignment history cannot be deleted.
 */
@Service
public class DriverService {

    private static final String RESOURCE_NAME = "Driver";
    private static final String RECORD_NOUN = "driver";
    static final String PHONE_FIELD = "phone";
    static final String LICENSE_NUMBER_FIELD = "licenseNumber";
    static final String DRIVER_HAS_HISTORY_TITLE = "Driver has history";

    private final DriverRepository driverRepository;
    private final AssignmentService assignmentService;
    private final DriverMapper mapper;
    private final ExpiryCalculator expiryCalculator;

    public DriverService(DriverRepository driverRepository, AssignmentService assignmentService, DriverMapper mapper,
                         ExpiryCalculator expiryCalculator) {
        this.driverRepository = driverRepository;
        this.assignmentService = assignmentService;
        this.mapper = mapper;
        this.expiryCalculator = expiryCalculator;
    }

    @Transactional(readOnly = true)
    public PageResponse<DriverResponse> findAll(String search, DriverStatus status, Boolean available, int page,
                                                int size, String sort) {
        PageRequest pageRequest = PageRequests.of(page, size, sort, DriverSortField.apiToProperty(),
                DriverSortField.DEFAULT_SORT);
        PageRequest stablePageRequest = pageRequest.withSort(pageRequest.getSort().and(DriverSortField.TIE_BREAKER));
        Specification<Driver> specification = Specification.allOf(
                DriverSpecifications.search(search),
                DriverSpecifications.hasStatus(status),
                DriverSpecifications.available(available));
        Page<Driver> drivers = driverRepository.findAll(specification, stablePageRequest);
        return PageResponse.from(withCurrentVehicles(drivers));
    }

    @Transactional(readOnly = true)
    public DriverResponse findById(Long id) {
        return toResponse(getDriver(id));
    }

    /** Up to {@code limit} drivers whose licence is expired or expires within 30 days, soonest first (FR-X02). */
    @Transactional(readOnly = true)
    public List<DriverResponse> findExpiringLicenses(int limit) {
        PageRequest firstPage = PageRequest.of(0, limit,
                Sort.by(Sort.Order.asc("licenseExpiry"), Sort.Order.asc("id")));
        List<Driver> drivers = driverRepository.findByLicenseExpiryLessThanEqual(expiryCalculator.expiringSoonUntil(),
                firstPage);
        Map<Long, Vehicle> vehicles = assignmentService.currentVehiclesByDriverId(
                drivers.stream().map(Driver::getId).toList());
        return drivers.stream().map(driver -> mapper.toResponse(driver, vehicles.get(driver.getId()))).toList();
    }

    @Transactional
    public DriverResponse create(DriverRequest request) {
        String phone = DriverPhone.normalize(request.phone());
        String licenseKey = LicenseNumber.normalize(request.licenseNumber());
        if (driverRepository.existsByPhone(phone)) {
            throw duplicatePhone(phone);
        }
        if (driverRepository.existsByLicenseKey(licenseKey)) {
            throw duplicateLicense(request.licenseNumber());
        }
        Driver driver = mapper.toEntity(request, phone, licenseKey);
        return mapper.toResponse(saveAndFlush(driver, phone, request.licenseNumber()), null);
    }

    /**
     * Setting the status to INACTIVE also closes the driver's current assignment in the same transaction (BR-05).
     * A {@code version} other than the stored one is refused with 409 stale-version before anything changes
     * (ADR 0023); under the row lock that comparison is final.
     */
    @Transactional
    public DriverResponse update(Long id, UpdateDriverRequest request) {
        // Row lock: a concurrent assignment of this driver waits, then sees the new status (BR-05).
        Driver driver = driverRepository.findForUpdateById(id)
                .orElseThrow(() -> new NotFoundException(RESOURCE_NAME, id));
        if (!Objects.equals(driver.getVersion(), request.version())) {
            throw new StaleVersionException(RECORD_NOUN);
        }
        String phone = DriverPhone.normalize(request.phone());
        String licenseKey = LicenseNumber.normalize(request.licenseNumber());
        if (driverRepository.existsByPhoneAndIdNot(phone, id)) {
            throw duplicatePhone(phone);
        }
        if (driverRepository.existsByLicenseKeyAndIdNot(licenseKey, id)) {
            throw duplicateLicense(request.licenseNumber());
        }
        mapper.updateEntity(driver, request, phone, licenseKey);
        if (!driver.isActive()) {
            assignmentService.closeCurrentForDriver(id);
        }
        return toResponse(saveAndFlush(driver, phone, request.licenseNumber()));
    }

    /**
     * Hard delete, only for drivers without any assignment history; otherwise 409 (set INACTIVE instead).
     */
    @Transactional
    public void delete(Long id) {
        Driver driver = getDriver(id);
        if (assignmentService.driverHasHistory(id)) {
            throw new BusinessRuleException(ApiErrorType.DRIVER_HAS_HISTORY, DRIVER_HAS_HISTORY_TITLE,
                    "Driver " + driver.getFullName() + " has vehicle assignment history and cannot be deleted. "
                            + "Set their status to INACTIVE instead.");
        }
        driverRepository.delete(driver);
    }

    private Page<DriverResponse> withCurrentVehicles(Page<Driver> drivers) {
        Map<Long, Vehicle> vehicles = assignmentService.currentVehiclesByDriverId(
                drivers.getContent().stream().map(Driver::getId).toList());
        return drivers.map(driver -> mapper.toResponse(driver, vehicles.get(driver.getId())));
    }

    private DriverResponse toResponse(Driver driver) {
        return mapper.toResponse(driver, assignmentService.currentVehicleOf(driver.getId()).orElse(null));
    }

    private Driver getDriver(Long id) {
        return driverRepository.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_NAME, id));
    }

    /**
     * Flushing inside the transaction turns a lost race on a unique constraint into the same 409 as the
     * service-level check.
     */
    private Driver saveAndFlush(Driver driver, String phone, String rawLicenseNumber) {
        try {
            return driverRepository.saveAndFlush(driver);
        } catch (DataIntegrityViolationException ex) {
            String message = ex.getMostSpecificCause().getMessage();
            if (message != null && message.contains(Driver.PHONE_UNIQUE_CONSTRAINT)) {
                throw duplicatePhone(phone);
            }
            if (message != null && message.contains(Driver.LICENSE_KEY_UNIQUE_CONSTRAINT)) {
                throw duplicateLicense(rawLicenseNumber);
            }
            throw ex;
        }
    }

    private static DuplicateResourceException duplicatePhone(String normalizedPhone) {
        return new DuplicateResourceException(ApiErrorType.DUPLICATE_DRIVER_PHONE, Driver.DUPLICATE_PHONE_TITLE,
                PHONE_FIELD, "Phone number " + normalizedPhone + " is already registered to another driver.");
    }

    private static DuplicateResourceException duplicateLicense(String rawLicenseNumber) {
        return new DuplicateResourceException(ApiErrorType.DUPLICATE_DRIVER_LICENSE, Driver.DUPLICATE_LICENSE_TITLE,
                LICENSE_NUMBER_FIELD, "Licence number " + rawLicenseNumber.trim()
                        + " is already registered to another driver.");
    }
}
