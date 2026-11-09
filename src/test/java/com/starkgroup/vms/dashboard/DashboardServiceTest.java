package com.starkgroup.vms.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.starkgroup.vms.assignment.VehicleDriverAssignmentRepository;
import com.starkgroup.vms.common.dto.PageResponse;
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
 * FR-DB01 dashboard maths: totals from the GROUP BY counts, date windows and "this month" in Asia/Dhaka.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    /** 30 Nov 2026 20:00 UTC is already 1 Dec 2026 02:00 in Dhaka: "this month" must be 2026-12. */
    private static final Instant NOW = Instant.parse("2026-11-30T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Dhaka"));
    private static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private VehicleDocumentRepository documentRepository;

    @Mock
    private MaintenanceRecordRepository maintenanceRepository;

    @Mock
    private DriverRepository driverRepository;

    @Mock
    private VehicleDriverAssignmentRepository assignmentRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private DriverService driverService;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(vehicleRepository, documentRepository, maintenanceRepository,
                driverRepository, assignmentRepository, documentService, driverService, new ExpiryCalculator(CLOCK),
                CLOCK);
        when(documentService.findExpiring(30, 0, 10)).thenReturn(new PageResponse<>(List.of(), 0, 10, 0, 0));
        when(driverService.findExpiringLicenses(10)).thenReturn(List.of());
    }

    @Test
    void frDb01_vehicleTotalIsTheSumOfTheStatusCountsAndMissingStatusesAreZero() {
        when(vehicleRepository.countByStatus()).thenReturn(List.of(count(VehicleStatus.ACTIVE, 18),
                count(VehicleStatus.MAINTENANCE, 5)));

        DashboardSummaryResponse.Vehicles vehicles = dashboardService.summary().vehicles();

        assertThat(vehicles.total()).isEqualTo(23);
        assertThat(vehicles.active()).isEqualTo(18);
        assertThat(vehicles.maintenance()).isEqualTo(5);
        assertThat(vehicles.inactive()).isZero();
    }

    @Test
    void frDb01_documentWindowsUseTodayInDhaka() {
        when(vehicleRepository.countByStatus()).thenReturn(List.of());
        when(documentRepository.countByExpiryDateBefore(TODAY)).thenReturn(2L);
        when(documentRepository.countByExpiryDateBetween(TODAY, TODAY.plusDays(30))).thenReturn(4L);

        DashboardSummaryResponse.Documents documents = dashboardService.summary().documents();

        assertThat(documents.expired()).isEqualTo(2);
        assertThat(documents.expiringSoon()).isEqualTo(4);
        assertThat(documents.withinDays()).isEqualTo(30);
    }

    @Test
    void frDb01_maintenanceThisMonthIsTheCalendarMonthInDhaka() {
        when(vehicleRepository.countByStatus()).thenReturn(List.of());
        LocalDate first = LocalDate.of(2026, 12, 1);
        LocalDate last = LocalDate.of(2026, 12, 31);
        when(maintenanceRepository.countByServiceDateBetween(first, last)).thenReturn(7L);
        when(maintenanceRepository.sumCostByServiceDateBetween(first, last)).thenReturn(new BigDecimal("48250.00"));

        DashboardSummaryResponse summary = dashboardService.summary();

        assertThat(summary.maintenance().month()).isEqualTo("2026-12");
        assertThat(summary.maintenance().count()).isEqualTo(7);
        assertThat(summary.maintenance().totalCost()).isEqualByComparingTo("48250");
        assertThat(summary.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void frX02_driverAndLicenceCounts() {
        when(vehicleRepository.countByStatus()).thenReturn(List.of());
        when(driverRepository.count()).thenReturn(12L);
        when(driverRepository.countByStatus(DriverStatus.ACTIVE)).thenReturn(11L);
        when(assignmentRepository.countByUnassignedAtIsNull()).thenReturn(9L);
        when(driverRepository.countByLicenseExpiryBefore(TODAY)).thenReturn(1L);
        when(driverRepository.countByLicenseExpiryBetween(TODAY, TODAY.plusDays(30))).thenReturn(2L);

        DashboardSummaryResponse summary = dashboardService.summary();

        assertThat(summary.drivers()).isEqualTo(new DashboardSummaryResponse.Drivers(12, 11, 9, 1, 2));
        verify(driverService).findExpiringLicenses(10);
        verify(documentService).findExpiring(30, 0, 10);
    }

    private static VehicleRepository.StatusCount count(VehicleStatus status, long total) {
        return new VehicleRepository.StatusCount() {
            @Override
            public VehicleStatus getStatus() {
                return status;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }
}
