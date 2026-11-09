package com.starkgroup.vms.driver;

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

import com.starkgroup.vms.assignment.AssignmentService;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.common.exception.DuplicateResourceException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.expiry.ExpiryStatus;
import com.starkgroup.vms.driver.dto.DriverRequest;
import com.starkgroup.vms.driver.dto.DriverResponse;
import com.starkgroup.vms.driver.dto.UpdateDriverRequest;

@ExtendWith(MockitoExtension.class)
class DriverServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T04:00:00Z"), ZoneId.of("Asia/Dhaka"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    @Mock
    private DriverRepository driverRepository;

    @Mock
    private AssignmentService assignmentService;

    private DriverService driverService;

    @BeforeEach
    void setUp() {
        ExpiryCalculator expiryCalculator = new ExpiryCalculator(CLOCK);
        driverService = new DriverService(driverRepository, assignmentService, new DriverMapper(expiryCalculator),
                expiryCalculator);
    }

    @Test
    void frDr01_createStoresNormalizedPhoneAndLicenseKeyWithDefaultStatus() {
        when(driverRepository.saveAndFlush(any(Driver.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DriverResponse response = driverService.create(request("+880 1711-000001", " dk-0123456-l00001 ", null));

        ArgumentCaptor<Driver> saved = ArgumentCaptor.forClass(Driver.class);
        verify(driverRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("01711000001");
        assertThat(saved.getValue().getLicenseNumber()).isEqualTo("dk-0123456-l00001");
        assertThat(saved.getValue().getLicenseKey()).isEqualTo("DK0123456L00001");
        assertThat(response.status()).isEqualTo(DriverStatus.ACTIVE);
        assertThat(response.licenseStatus()).isEqualTo(ExpiryStatus.VALID);
        assertThat(response.daysUntilLicenseExpiry()).isEqualTo(133);
        assertThat(response.currentVehicle()).isNull();
    }

    @Test
    void frDr01_duplicatePhoneIsRejectedWithNormalizedNumber() {
        when(driverRepository.existsByPhone("01711000001")).thenReturn(true);

        assertThatThrownBy(() -> driverService.create(request("01711-000001", "DK1", null)))
                .isInstanceOfSatisfying(DuplicateResourceException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.DUPLICATE_DRIVER_PHONE);
                    assertThat(ex.getField()).isEqualTo("phone");
                    assertThat(ex.getMessage())
                            .isEqualTo("Phone number 01711000001 is already registered to another driver.");
                });
        verify(driverRepository, never()).saveAndFlush(any());
    }

    @Test
    void frDr01_duplicateLicenseIsRejectedAfterNormalization() {
        when(driverRepository.existsByLicenseKey("DK0123456L00001")).thenReturn(true);

        assertThatThrownBy(() -> driverService.create(request("01711000001", "dk 0123456 l00001 ", null)))
                .isInstanceOfSatisfying(DuplicateResourceException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.DUPLICATE_DRIVER_LICENSE);
                    assertThat(ex.getField()).isEqualTo("licenseNumber");
                    assertThat(ex.getMessage())
                            .isEqualTo("Licence number dk 0123456 l00001 is already registered to another driver.");
                });
    }

    @Test
    void frDr01_updateIgnoresTheDriverBeingEdited() {
        Driver existing = driver(4L, DriverStatus.ACTIVE);
        when(driverRepository.findForUpdateById(4L)).thenReturn(Optional.of(existing));
        when(driverRepository.saveAndFlush(existing)).thenReturn(existing);

        DriverResponse response = driverService.update(4L,
                updateRequest("01711000001", "DK0123456L00001", null, 0L));

        verify(driverRepository).existsByPhoneAndIdNot("01711000001", 4L);
        verify(driverRepository).existsByLicenseKeyAndIdNot("DK0123456L00001", 4L);
        verify(driverRepository, never()).existsByPhone(any());
        assertThat(response.fullName()).isEqualTo("Rahim Uddin");
    }

    @Test
    void frDr01_updateToAnotherDriversPhoneIsRejected() {
        when(driverRepository.findForUpdateById(4L)).thenReturn(Optional.of(driver(4L, DriverStatus.ACTIVE)));
        when(driverRepository.existsByPhoneAndIdNot("01811000002", 4L)).thenReturn(true);

        assertThatThrownBy(() -> driverService.update(4L, updateRequest("01811-000002", "DK1", null, 0L)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(driverRepository, never()).saveAndFlush(any());
    }

    @Test
    void br05_settingDriverInactiveClosesTheCurrentAssignmentInTheSameTransaction() {
        Driver existing = driver(4L, DriverStatus.ACTIVE);
        when(driverRepository.findForUpdateById(4L)).thenReturn(Optional.of(existing));
        when(driverRepository.saveAndFlush(existing)).thenReturn(existing);

        DriverResponse response = driverService.update(4L,
                updateRequest("01711000001", "DK0123456L00001", DriverStatus.INACTIVE, 0L));

        InOrder order = inOrder(assignmentService, driverRepository);
        order.verify(assignmentService).closeCurrentForDriver(4L);
        order.verify(driverRepository).saveAndFlush(existing);
        assertThat(response.status()).isEqualTo(DriverStatus.INACTIVE);
    }

    @Test
    void activeDriverUpdateKeepsTheAssignment() {
        Driver existing = driver(4L, DriverStatus.ACTIVE);
        when(driverRepository.findForUpdateById(4L)).thenReturn(Optional.of(existing));
        when(driverRepository.saveAndFlush(existing)).thenReturn(existing);

        driverService.update(4L, updateRequest("01711000001", "DK0123456L00001", DriverStatus.ACTIVE, 0L));

        verify(assignmentService, never()).closeCurrentForDriver(any());
    }

    @Test
    void adr0023_staleVersionIsRefusedBeforeTheAssignmentIsClosed() {
        Driver existing = driver(4L, DriverStatus.ACTIVE);
        ReflectionTestUtils.setField(existing, "version", 2L);
        when(driverRepository.findForUpdateById(4L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> driverService.update(4L,
                updateRequest("01711000001", "DK0123456L00001", DriverStatus.INACTIVE, 1L)))
                .isInstanceOf(StaleVersionException.class)
                .hasMessage("This driver was changed by someone else since you opened it. Reload and try again.");
        assertThat(existing.getStatus()).isEqualTo(DriverStatus.ACTIVE);
        verify(assignmentService, never()).closeCurrentForDriver(any());
        verify(driverRepository, never()).saveAndFlush(any());
    }

    @Test
    void frDr01_lostRaceOnThePhoneConstraintBecomesTheSame409() {
        when(driverRepository.saveAndFlush(any(Driver.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uk_drivers_phone\"")));

        assertThatThrownBy(() -> driverService.create(request("01711000001", "DK1", null)))
                .isInstanceOfSatisfying(DuplicateResourceException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("phone"));
    }

    @Test
    void driverWithAssignmentHistoryCannotBeDeleted() {
        Driver existing = driver(4L, DriverStatus.ACTIVE);
        when(driverRepository.findById(4L)).thenReturn(Optional.of(existing));
        when(assignmentService.driverHasHistory(4L)).thenReturn(true);

        assertThatThrownBy(() -> driverService.delete(4L))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.DRIVER_HAS_HISTORY);
                    assertThat(ex.getMessage()).contains("Rahim Uddin").contains("INACTIVE");
                });
        verify(driverRepository, never()).delete(any(Driver.class));
    }

    @Test
    void driverWithoutHistoryIsDeleted() {
        Driver existing = driver(4L, DriverStatus.ACTIVE);
        when(driverRepository.findById(4L)).thenReturn(Optional.of(existing));

        driverService.delete(4L);

        verify(driverRepository).delete(existing);
    }

    @Test
    void missingDriverIsNotFound() {
        when(driverRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> driverService.findById(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Driver with id 99 was not found.");
    }

    @Test
    void frX02_expiredLicenceIsReportedAsExpired() {
        Driver existing = driver(4L, DriverStatus.ACTIVE);
        existing.setLicenseExpiry(TODAY.minusDays(3));
        when(driverRepository.findById(4L)).thenReturn(Optional.of(existing));

        DriverResponse response = driverService.findById(4L);

        assertThat(response.licenseStatus()).isEqualTo(ExpiryStatus.EXPIRED);
        assertThat(response.daysUntilLicenseExpiry()).isEqualTo(-3);
    }

    private static DriverRequest request(String phone, String license, DriverStatus status) {
        return new DriverRequest(" Rahim Uddin ", phone, license, LocalDate.of(2027, 3, 31), status);
    }

    private static UpdateDriverRequest updateRequest(String phone, String license, DriverStatus status,
                                                     Long version) {
        return new UpdateDriverRequest(" Rahim Uddin ", phone, license, LocalDate.of(2027, 3, 31), status, version);
    }

    private static Driver driver(Long id, DriverStatus status) {
        Driver driver = new Driver("Rahim Uddin", "01711000001", "DK0123456L00001", "DK0123456L00001",
                LocalDate.of(2027, 3, 31));
        driver.setStatus(status);
        ReflectionTestUtils.setField(driver, "id", id);
        ReflectionTestUtils.setField(driver, "version", 0L);
        return driver;
    }
}
