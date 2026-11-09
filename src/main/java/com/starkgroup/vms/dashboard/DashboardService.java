package com.starkgroup.vms.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.assignment.VehicleDriverAssignmentRepository;
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.dashboard.dto.DashboardSummaryResponse;
import com.starkgroup.vms.document.DocumentService;
import com.starkgroup.vms.document.VehicleDocumentRepository;
import com.starkgroup.vms.driver.DriverRepository;
import com.starkgroup.vms.driver.DriverService;
import com.starkgroup.vms.driver.DriverStatus;
import com.starkgroup.vms.maintenance.MaintenanceRecordRepository;
import com.starkgroup.vms.vehicle.VehicleRepository;
import com.starkgroup.vms.vehicle.VehicleStatus;

/**
 * FR-DB01 / FR-X02: all dashboard numbers from COUNT/SUM queries (never whole tables), in one read-only
 * transaction with one REPEATABLE READ snapshot, so the numbers are consistent with each other. "Today" and
 * "this month" are in Asia/Dhaka.
 */
@Service
public class DashboardService {

    static final int TOP_LIST_SIZE = 10;

    private final VehicleRepository vehicleRepository;
    private final VehicleDocumentRepository documentRepository;
    private final MaintenanceRecordRepository maintenanceRepository;
    private final DriverRepository driverRepository;
    private final VehicleDriverAssignmentRepository assignmentRepository;
    private final DocumentService documentService;
    private final DriverService driverService;
    private final ExpiryCalculator expiryCalculator;
    private final Clock clock;

    public DashboardService(VehicleRepository vehicleRepository, VehicleDocumentRepository documentRepository,
                            MaintenanceRecordRepository maintenanceRepository, DriverRepository driverRepository,
                            VehicleDriverAssignmentRepository assignmentRepository, DocumentService documentService,
                            DriverService driverService, ExpiryCalculator expiryCalculator, Clock clock) {
        this.vehicleRepository = vehicleRepository;
        this.documentRepository = documentRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.driverRepository = driverRepository;
        this.assignmentRepository = assignmentRepository;
        this.documentService = documentService;
        this.driverService = driverService;
        this.expiryCalculator = expiryCalculator;
        this.clock = clock;
    }

    // REPEATABLE READ: PostgreSQL READ COMMITTED would give every one of the queries its own snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardSummaryResponse summary() {
        LocalDate today = expiryCalculator.today();
        LocalDate expiringUntil = expiryCalculator.expiringSoonUntil();
        return new DashboardSummaryResponse(
                vehicles(),
                new DashboardSummaryResponse.Documents(
                        documentRepository.countByExpiryDateBefore(today),
                        documentRepository.countByExpiryDateBetween(today, expiringUntil),
                        ExpiryCalculator.EXPIRING_SOON_DAYS),
                maintenanceThisMonth(YearMonth.from(today)),
                new DashboardSummaryResponse.Drivers(
                        driverRepository.count(),
                        driverRepository.countByStatus(DriverStatus.ACTIVE),
                        assignmentRepository.countByUnassignedAtIsNull(),
                        driverRepository.countByLicenseExpiryBefore(today),
                        driverRepository.countByLicenseExpiryBetween(today, expiringUntil)),
                documentService.findExpiring(ExpiryCalculator.EXPIRING_SOON_DAYS, 0, TOP_LIST_SIZE).content(),
                driverService.findExpiringLicenses(TOP_LIST_SIZE),
                clock.instant());
    }

    private DashboardSummaryResponse.Vehicles vehicles() {
        Map<VehicleStatus, Long> byStatus = new EnumMap<>(VehicleStatus.class);
        for (VehicleRepository.StatusCount row : vehicleRepository.countByStatus()) {
            byStatus.put(row.getStatus(), row.getTotal());
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new DashboardSummaryResponse.Vehicles(total,
                byStatus.getOrDefault(VehicleStatus.ACTIVE, 0L),
                byStatus.getOrDefault(VehicleStatus.INACTIVE, 0L),
                byStatus.getOrDefault(VehicleStatus.MAINTENANCE, 0L));
    }

    private DashboardSummaryResponse.Maintenance maintenanceThisMonth(YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        return new DashboardSummaryResponse.Maintenance(month.toString(),
                maintenanceRepository.countByServiceDateBetween(first, last),
                maintenanceRepository.sumCostByServiceDateBetween(first, last));
    }
}
