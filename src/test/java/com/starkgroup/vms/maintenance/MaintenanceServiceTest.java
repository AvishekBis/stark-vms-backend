package com.starkgroup.vms.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.starkgroup.vms.common.exception.FieldValidationException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.maintenance.dto.MaintenanceHistoryResponse;
import com.starkgroup.vms.maintenance.dto.MaintenanceRequest;
import com.starkgroup.vms.maintenance.dto.MaintenanceResponse;
import com.starkgroup.vms.maintenance.dto.UpdateMaintenanceRequest;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleRepository;
import com.starkgroup.vms.vehicle.VehicleType;

/**
 * FR-M01 / FR-M02 / BR-09 with a fixed Clock.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceServiceTest {

    /** 18 Nov 2026 23:30 in Dhaka = 17:30 UTC: "today" must be the 18th, not the UTC date. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T17:30:00Z"), ZoneId.of("Asia/Dhaka"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    @Mock
    private MaintenanceRecordRepository maintenanceRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    private MaintenanceService maintenanceService;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        maintenanceService = new MaintenanceService(maintenanceRepository, vehicleRepository, new MaintenanceMapper(),
                new ExpiryCalculator(CLOCK));
        vehicle = new Vehicle("DHK-1234", "DHK1234", VehicleType.CAR);
        ReflectionTestUtils.setField(vehicle, "id", 12L);
    }

    @Test
    void frM01_createStoresRecordWithTrimmedText() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));
        when(maintenanceRepository.saveAndFlush(any(MaintenanceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MaintenanceResponse response = maintenanceService.create(12L, request(TODAY, "3500.00"));

        assertThat(response.vehicleId()).isEqualTo(12L);
        assertThat(response.cost()).isEqualByComparingTo("3500");
        assertThat(response.vendorName()).isEqualTo("Navana Service Centre");
        assertThat(response.notes()).isNull();
    }

    @Test
    void br09_serviceDateInTheFutureIsRejected() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> maintenanceService.create(12L, request(TODAY.plusDays(1), "100")))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("serviceDate");
                    assertThat(ex.getMessage()).isEqualTo("Service date cannot be in the future.");
                });
        verify(maintenanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void br09_negativeCostIsRejected() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> maintenanceService.create(12L, request(TODAY, "-0.01")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("cost"));
    }

    @Test
    void br09_costWithMoreThanTwoDecimalsIsRejectedButZeroIsAllowed() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));
        when(maintenanceRepository.saveAndFlush(any(MaintenanceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> maintenanceService.create(12L, request(TODAY, "10.005")))
                .isInstanceOf(FieldValidationException.class);
        assertThat(maintenanceService.create(12L, request(TODAY.minusDays(30), "0")).cost()).isZero();
    }

    @Test
    void frM02_historyIsNewestFirstWithTotalOverAllRecords() {
        when(vehicleRepository.existsById(12L)).thenReturn(true);
        when(maintenanceRepository.findByVehicleId(eq(12L), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(record(TODAY, "500.00")), invocation.getArgument(1), 3));
        when(maintenanceRepository.sumCostByVehicleId(12L)).thenReturn(new BigDecimal("12500.00"));

        MaintenanceHistoryResponse history = maintenanceService.history(12L, 0, 1);

        assertThat(history.totalCost()).isEqualByComparingTo("12500");
        assertThat(history.recordCount()).isEqualTo(3);
        assertThat(history.totalElements()).isEqualTo(3);
        assertThat(history.totalPages()).isEqualTo(3);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(maintenanceRepository).findByVehicleId(eq(12L), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .containsExactly(Sort.Order.desc("serviceDate"), Sort.Order.desc("id"));
    }

    @Test
    void historyOfMissingVehicleIsNotFound() {
        assertThatThrownBy(() -> maintenanceService.history(99L, 0, 20)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateChangesTheRecord() {
        MaintenanceRecord existing = record(TODAY.minusDays(5), "100.00");
        when(maintenanceRepository.findWithVehicleById(57L)).thenReturn(Optional.of(existing));
        when(maintenanceRepository.saveAndFlush(existing)).thenReturn(existing);

        MaintenanceResponse response = maintenanceService.update(57L, updateRequest(TODAY, "4200.50", 0L));

        assertThat(response.cost()).isEqualByComparingTo("4200.50");
        assertThat(response.serviceDate()).isEqualTo(TODAY);
        assertThat(response.version()).isZero();
    }

    @Test
    void adr0023_updateWithAnOlderVersionIsRefused() {
        MaintenanceRecord existing = record(TODAY.minusDays(5), "100.00");
        ReflectionTestUtils.setField(existing, "version", 1L);
        when(maintenanceRepository.findWithVehicleById(57L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> maintenanceService.update(57L, updateRequest(TODAY, "4200.50", 0L)))
                .isInstanceOf(StaleVersionException.class)
                .hasMessage("This maintenance record was changed by someone else since you opened it. "
                        + "Reload and try again.");
        assertThat(existing.getCost()).isEqualByComparingTo("100.00");
        verify(maintenanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void costIsStoredAndReturnedWithTwoDecimalsLikeTheDatabaseColumn() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));
        when(maintenanceRepository.saveAndFlush(any(MaintenanceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(maintenanceService.create(12L, request(TODAY, "3500")).cost()).hasToString("3500.00");
        assertThat(maintenanceService.create(12L, request(TODAY, "12.500")).cost()).hasToString("12.50");
    }

    private static MaintenanceRequest request(LocalDate serviceDate, String cost) {
        return new MaintenanceRequest(serviceDate, MaintenanceType.OIL_CHANGE, new BigDecimal(cost),
                " Navana Service Centre ", 45210, "  ");
    }

    private static UpdateMaintenanceRequest updateRequest(LocalDate serviceDate, String cost, Long version) {
        return new UpdateMaintenanceRequest(serviceDate, MaintenanceType.OIL_CHANGE, new BigDecimal(cost),
                " Navana Service Centre ", 45210, "  ", version);
    }

    private MaintenanceRecord record(LocalDate serviceDate, String cost) {
        MaintenanceRecord record = new MaintenanceRecord(vehicle);
        ReflectionTestUtils.setField(record, "version", 0L);
        record.setServiceDate(serviceDate);
        record.setMaintenanceType(MaintenanceType.REPAIR);
        record.setCost(new BigDecimal(cost));
        ReflectionTestUtils.setField(record, "id", 57L);
        return record;
    }
}
