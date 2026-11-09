package com.starkgroup.vms.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.starkgroup.vms.assignment.dto.AssignmentResponse;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.driver.Driver;
import com.starkgroup.vms.driver.DriverRepository;
import com.starkgroup.vms.driver.DriverStatus;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleNumber;
import com.starkgroup.vms.vehicle.VehicleRepository;
import com.starkgroup.vms.vehicle.VehicleStatus;
import com.starkgroup.vms.vehicle.VehicleType;

/**
 * FR-DR02 rules 1-5 of contract 6.4 (BR-05, BR-06, TC-10).
 */
@ExtendWith(MockitoExtension.class)
class AssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Dhaka"));

    @Mock
    private VehicleDriverAssignmentRepository assignmentRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private DriverRepository driverRepository;

    private AssignmentService assignmentService;

    private Vehicle vehicle;
    private Driver driver;

    @BeforeEach
    void setUp() {
        assignmentService = new AssignmentService(assignmentRepository, vehicleRepository, driverRepository,
                new AssignmentMapper(), CLOCK);
        vehicle = vehicle(12L, "DHK-1234", VehicleStatus.ACTIVE);
        driver = driver(4L, "Rahim Uddin", DriverStatus.ACTIVE);
    }

    @Test
    void frDr02_assignsActiveDriverToActiveVehicle() {
        givenVehicleAndDriver();
        when(assignmentRepository.saveAndFlush(any(VehicleDriverAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssignmentResponse response = assignmentService.assign(12L, 4L);

        assertThat(response.vehicleId()).isEqualTo(12L);
        assertThat(response.vehicleNumber()).isEqualTo("DHK-1234");
        assertThat(response.driverId()).isEqualTo(4L);
        assertThat(response.driverName()).isEqualTo("Rahim Uddin");
        assertThat(response.assignedAt()).isEqualTo(NOW);
        assertThat(response.unassignedAt()).isNull();
        assertThat(response.current()).isTrue();
    }

    @Test
    void tc10_driverAlreadyDrivingAnotherVehicleIsRejected() {
        givenVehicleAndDriver();
        Vehicle other = vehicle(13L, "DHK-5678", VehicleStatus.ACTIVE);
        when(assignmentRepository.findByDriverIdAndUnassignedAtIsNull(4L))
                .thenReturn(Optional.of(new VehicleDriverAssignment(other, driver, NOW.minusSeconds(3600))));

        assertThatThrownBy(() -> assignmentService.assign(12L, 4L))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.DRIVER_ALREADY_ASSIGNED);
                    assertThat(ex.getField()).isEqualTo("driverId");
                    assertThat(ex.getMessage()).isEqualTo(
                            "Driver Rahim Uddin is already assigned to vehicle DHK-5678. Unassign them first.");
                });
        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void br05_inactiveVehicleCannotGetADriver() {
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        givenVehicleAndDriver();

        assertThatThrownBy(() -> assignmentService.assign(12L, 4L))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.ASSIGNMENT_NOT_ALLOWED);
                    assertThat(ex.getMessage()).contains("Vehicle DHK-1234 is MAINTENANCE");
                });
    }

    @Test
    void br05_inactiveDriverCannotBeAssigned() {
        driver.setStatus(DriverStatus.INACTIVE);
        givenVehicleAndDriver();

        assertThatThrownBy(() -> assignmentService.assign(12L, 4L))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.ASSIGNMENT_NOT_ALLOWED);
                    assertThat(ex.getMessage()).contains("Driver Rahim Uddin is INACTIVE");
                });
        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void frDr02_assigningTheCurrentDriverAgainIsIdempotent() {
        givenVehicleAndDriver();
        VehicleDriverAssignment existing = new VehicleDriverAssignment(vehicle, driver, NOW.minusSeconds(60));
        ReflectionTestUtils.setField(existing, "id", 9L);
        when(assignmentRepository.findByVehicleIdAndUnassignedAtIsNull(12L)).thenReturn(Optional.of(existing));

        AssignmentResponse response = assignmentService.assign(12L, 4L);

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.current()).isTrue();
        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void br05_changingTheDriverClosesThePreviousAssignmentBeforeInserting() {
        givenVehicleAndDriver();
        Driver previousDriver = driver(5L, "Karim Hossain", DriverStatus.ACTIVE);
        VehicleDriverAssignment previous = new VehicleDriverAssignment(vehicle, previousDriver, NOW.minusSeconds(60));
        when(assignmentRepository.findByVehicleIdAndUnassignedAtIsNull(12L)).thenReturn(Optional.of(previous));
        when(assignmentRepository.saveAndFlush(any(VehicleDriverAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssignmentResponse response = assignmentService.assign(12L, 4L);

        assertThat(previous.getUnassignedAt()).isEqualTo(NOW);
        assertThat(previous.isCurrent()).isFalse();
        assertThat(response.driverId()).isEqualTo(4L);
        InOrder order = inOrder(assignmentRepository);
        order.verify(assignmentRepository).flush();
        ArgumentCaptor<VehicleDriverAssignment> inserted = ArgumentCaptor.forClass(VehicleDriverAssignment.class);
        order.verify(assignmentRepository).saveAndFlush(inserted.capture());
        assertThat(inserted.getValue().getDriver()).isSameAs(driver);
    }

    @Test
    void br06_lostRaceOnThePartialUniqueIndexIsDriverAlreadyAssigned() {
        givenVehicleAndDriver();
        when(assignmentRepository.saveAndFlush(any(VehicleDriverAssignment.class))).thenThrow(
                new DataIntegrityViolationException("could not execute statement", new SQLException(
                        "duplicate key value violates unique constraint \"uq_assignment_open_driver\"")));

        assertThatThrownBy(() -> assignmentService.assign(12L, 4L))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getType()).isEqualTo(ApiErrorType.DRIVER_ALREADY_ASSIGNED));
    }

    @Test
    void missingVehicleOrDriverIsNotFound() {
        when(vehicleRepository.findForUpdateById(12L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> assignmentService.assign(12L, 4L)).isInstanceOf(NotFoundException.class);

        when(vehicleRepository.findForUpdateById(12L)).thenReturn(Optional.of(vehicle));
        when(driverRepository.findForUpdateById(4L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> assignmentService.assign(12L, 4L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Driver with id 4 was not found.");
    }

    @Test
    void unassignClosesTheCurrentAssignment() {
        when(vehicleRepository.findForUpdateById(12L)).thenReturn(Optional.of(vehicle));
        VehicleDriverAssignment current = new VehicleDriverAssignment(vehicle, driver, NOW.minusSeconds(60));
        when(assignmentRepository.findByVehicleIdAndUnassignedAtIsNull(12L)).thenReturn(Optional.of(current));

        assignmentService.unassign(12L);

        assertThat(current.getUnassignedAt()).isEqualTo(NOW);
        verify(assignmentRepository).flush();
    }

    @Test
    void unassignWithoutCurrentDriverIsNoCurrentDriver() {
        when(vehicleRepository.findForUpdateById(12L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> assignmentService.unassign(12L))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.NO_CURRENT_DRIVER);
                    assertThat(ex.getMessage()).isEqualTo("Vehicle DHK-1234 has no current driver.");
                });
    }

    @Test
    void closeCurrentForDriverDoesNothingWithoutAnOpenAssignment() {
        assignmentService.closeCurrentForDriver(4L);

        verify(assignmentRepository, never()).flush();
    }

    private void givenVehicleAndDriver() {
        when(vehicleRepository.findForUpdateById(12L)).thenReturn(Optional.of(vehicle));
        when(driverRepository.findForUpdateById(4L)).thenReturn(Optional.of(driver));
    }

    private static Vehicle vehicle(Long id, String number, VehicleStatus status) {
        Vehicle vehicle = new Vehicle(number, VehicleNumber.normalize(number), VehicleType.CAR);
        vehicle.setStatus(status);
        ReflectionTestUtils.setField(vehicle, "id", id);
        return vehicle;
    }

    private static Driver driver(Long id, String name, DriverStatus status) {
        Driver driver = new Driver(name, "0171100000" + id, "DK" + id, "DK" + id, LocalDate.of(2027, 1, 1));
        driver.setStatus(status);
        ReflectionTestUtils.setField(driver, "id", id);
        return driver;
    }
}
