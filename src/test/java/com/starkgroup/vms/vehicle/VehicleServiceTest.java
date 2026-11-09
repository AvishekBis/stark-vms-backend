package com.starkgroup.vms.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.starkgroup.vms.assignment.AssignmentService;
import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.exception.ApiErrorType;
import com.starkgroup.vms.common.exception.BadRequestException;
import com.starkgroup.vms.common.exception.BusinessRuleException;
import com.starkgroup.vms.common.exception.DuplicateResourceException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.exception.StaleVersionException;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.vehicle.dto.CreateVehicleRequest;
import com.starkgroup.vms.vehicle.dto.UpdateVehicleRequest;
import com.starkgroup.vms.vehicle.dto.VehicleResponse;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private AssignmentService assignmentService;

    @Mock
    private VehicleUsage vehicleUsage;

    private VehicleService vehicleService;

    @BeforeEach
    void setUp() {
        vehicleService = new VehicleService(vehicleRepository, new VehicleMapper(), assignmentService,
                List.of(id -> false, vehicleUsage));
    }

    @Test
    void tc01_createStoresTrimmedNumberAndNormalizedKeyWithActiveStatus() {
        when(vehicleRepository.existsByNumberKey("DHK1234")).thenReturn(false);
        when(vehicleRepository.saveAndFlush(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = vehicleService.create(createRequest("  DHK-1234 "));

        ArgumentCaptor<Vehicle> saved = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getVehicleNumber()).isEqualTo("DHK-1234");
        assertThat(saved.getValue().getNumberKey()).isEqualTo("DHK1234");
        assertThat(response.status()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(response.manufactureYear()).isEqualTo(2019);
        assertThat(response.brand()).isEqualTo("Toyota");
    }

    @Test
    void createStoresBlankOptionalFieldsAsNull() {
        when(vehicleRepository.saveAndFlush(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = vehicleService.create(
                new CreateVehicleRequest("DHK-1", VehicleType.BUS, "  ", null, null, "", null));

        assertThat(response.brand()).isNull();
        assertThat(response.color()).isNull();
        assertThat(response.manufactureYear()).isNull();
    }

    @Test
    void tc02_createWithExistingNumberIsRejected() {
        when(vehicleRepository.existsByNumberKey("DHK1234")).thenReturn(true);

        assertThatThrownBy(() -> vehicleService.create(createRequest("DHK-1234")))
                .isInstanceOfSatisfying(DuplicateResourceException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("Vehicle number DHK-1234 is already registered.");
                    assertThat(ex.getField()).isEqualTo("vehicleNumber");
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.DUPLICATE_VEHICLE_NUMBER);
                });
        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    void tc03_createWithDifferentlyFormattedExistingNumberIsRejected() {
        when(vehicleRepository.existsByNumberKey("DHK1234")).thenReturn(true);

        assertThatThrownBy(() -> vehicleService.create(createRequest("dhk 1234")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("Vehicle number dhk 1234 is already registered.");
        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    void tc04_updateKeepingOwnNumberIsAllowed() {
        Vehicle existing = vehicle("DHK-1234");
        when(vehicleRepository.findForUpdateById(7L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.existsByNumberKeyAndIdNot("DHK1234", 7L)).thenReturn(false);
        when(vehicleRepository.saveAndFlush(existing)).thenReturn(existing);

        VehicleResponse response = vehicleService.update(7L, updateRequest("DHK-1234", "Premio"));

        assertThat(response.vehicleNumber()).isEqualTo("DHK-1234");
        assertThat(response.model()).isEqualTo("Premio");
        verify(vehicleRepository, never()).existsByNumberKey(anyString());
    }

    @Test
    void adr0023_updateWithTheStoredVersionIsAccepted() {
        Vehicle existing = vehicle("DHK-1234");
        ReflectionTestUtils.setField(existing, "version", 3L);
        when(vehicleRepository.findForUpdateById(7L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.saveAndFlush(existing)).thenReturn(existing);

        VehicleResponse response = vehicleService.update(7L, updateRequest("DHK-1234", "Premio", 3L));

        assertThat(response.model()).isEqualTo("Premio");
        assertThat(response.version()).isEqualTo(3L);
    }

    @Test
    void adr0023_updateWithAnOlderVersionIsRefusedBeforeAnythingChanges() {
        Vehicle existing = vehicle("DHK-1234");
        ReflectionTestUtils.setField(existing, "version", 4L);
        when(vehicleRepository.findForUpdateById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> vehicleService.update(7L, updateRequest("CTG-9999", "Premio", 3L)))
                .isInstanceOf(StaleVersionException.class)
                .hasMessage("This vehicle was changed by someone else since you opened it. Reload and try again.");
        assertThat(existing.getModel()).isEqualTo("Axio");
        assertThat(existing.getVehicleNumber()).isEqualTo("DHK-1234");
        verify(vehicleRepository, never()).existsByNumberKeyAndIdNot(anyString(), any());
        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    void br02_updateToAnotherVehiclesNumberIsRejected() {
        when(vehicleRepository.findForUpdateById(7L)).thenReturn(Optional.of(vehicle("DHK-1234")));
        when(vehicleRepository.existsByNumberKeyAndIdNot("CTG9999", 7L)).thenReturn(true);

        assertThatThrownBy(() -> vehicleService.update(7L, updateRequest("ctg 9999", "Axio")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("Vehicle number ctg 9999 is already registered.");
        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateOfMissingVehicleIsNotFound() {
        when(vehicleRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.update(99L, updateRequest("DHK-1", "Axio")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Vehicle with id 99 was not found.");
    }

    @Test
    void findByIdOfMissingVehicleIsNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.findById(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteOfMissingVehicleIsNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.delete(99L)).isInstanceOf(NotFoundException.class);
        verify(vehicleRepository, never()).delete(any(Vehicle.class));
    }

    @Test
    void deleteRemovesExistingVehicle() {
        Vehicle existing = vehicle("DHK-1234");
        when(vehicleRepository.findById(7L)).thenReturn(Optional.of(existing));

        vehicleService.delete(7L);

        verify(vehicleRepository).delete(existing);
    }

    @Test
    void br01_lostRaceOnUniqueConstraintBecomesTheSameDuplicateError() {
        when(vehicleRepository.existsByNumberKey("DHK1234")).thenReturn(false);
        when(vehicleRepository.saveAndFlush(any(Vehicle.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uk_vehicles_number_key\"")));

        assertThatThrownBy(() -> vehicleService.create(createRequest("DHK-1234")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("Vehicle number DHK-1234 is already registered.");
    }

    @Test
    void otherIntegrityViolationsAreNotReportedAsDuplicates() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "could not execute statement", new SQLException("violates check constraint \"ck_vehicles_status\""));
        when(vehicleRepository.saveAndFlush(any(Vehicle.class))).thenThrow(failure);

        assertThatThrownBy(() -> vehicleService.create(createRequest("DHK-1234"))).isSameAs(failure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tc11_listCapsPageSizeAndAddsStableTieBreaker() {
        when(vehicleRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(vehicle("DHK-1")), invocation.getArgument(1), 1));

        PageResponse<VehicleResponse> page = vehicleService.findAll(null, 0, 500, "vehicleNumber,asc");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(vehicleRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(PageRequests.MAX_PAGE_SIZE);
        assertThat(pageable.getValue().getSort())
                .containsExactly(Sort.Order.asc("vehicleNumber"), Sort.Order.desc("id"));
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).extracting(VehicleResponse::vehicleNumber).containsExactly("DHK-1");
    }

    @Test
    void listWithUnknownSortFieldIsBadRequest() {
        assertThatThrownBy(() -> vehicleService.findAll(null, 0, 20, "color,asc"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown sort field 'color'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listWithoutSortUsesCreatedAtDescending() {
        Page<Vehicle> empty = Page.empty();
        when(vehicleRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        vehicleService.findAll("dhk", 0, 20, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(vehicleRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void frV07_statusInactiveClosesTheCurrentAssignmentFirst() {
        Vehicle existing = vehicle("DHK-1234");
        when(vehicleRepository.findForUpdateById(7L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.saveAndFlush(existing)).thenReturn(existing);

        VehicleResponse response = vehicleService.changeStatus(7L, VehicleStatus.INACTIVE);

        verify(assignmentService).closeCurrentForVehicle(7L);
        assertThat(response.status()).isEqualTo(VehicleStatus.INACTIVE);
        assertThat(response.currentDriver()).isNull();
    }

    @Test
    void frV07_statusMaintenanceKeepsTheDriver() {
        Vehicle existing = vehicle("DHK-1234");
        when(vehicleRepository.findForUpdateById(7L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.saveAndFlush(existing)).thenReturn(existing);

        assertThat(vehicleService.changeStatus(7L, VehicleStatus.MAINTENANCE).status())
                .isEqualTo(VehicleStatus.MAINTENANCE);
        verify(assignmentService, never()).closeCurrentForVehicle(any());
    }

    @Test
    void frV07_sameStatusAgainChangesNothing() {
        when(vehicleRepository.findForUpdateById(7L)).thenReturn(Optional.of(vehicle("DHK-1234")));

        assertThat(vehicleService.changeStatus(7L, VehicleStatus.ACTIVE).status()).isEqualTo(VehicleStatus.ACTIVE);
        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    void br10_vehicleWithHistoryCannotBeDeleted() {
        when(vehicleRepository.findById(7L)).thenReturn(Optional.of(vehicle("DHK-1234")));
        when(vehicleUsage.isUsedBy(7L)).thenReturn(true);

        assertThatThrownBy(() -> vehicleService.delete(7L))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo(ApiErrorType.VEHICLE_HAS_HISTORY);
                    assertThat(ex.getTitle()).isEqualTo("Vehicle has history");
                    assertThat(ex.getMessage()).isEqualTo("Vehicle DHK-1234 has documents, maintenance or driver "
                            + "history and cannot be deleted. Set its status to INACTIVE instead.");
                });
        verify(vehicleRepository, never()).delete(any(Vehicle.class));
    }

    private static CreateVehicleRequest createRequest(String number) {
        return new CreateVehicleRequest(number, VehicleType.CAR, " Toyota ", "Axio", 2019, "White", FuelType.OCTANE);
    }

    private static UpdateVehicleRequest updateRequest(String number, String model) {
        return updateRequest(number, model, 0L);
    }

    private static UpdateVehicleRequest updateRequest(String number, String model, Long version) {
        return new UpdateVehicleRequest(number, VehicleType.CAR, "Toyota", model, 2019, "White", FuelType.OCTANE,
                version);
    }

    /** A loaded vehicle: version 0, as after migration V7 or a first insert. */
    private static Vehicle vehicle(String number) {
        Vehicle vehicle = new Vehicle(number, VehicleNumber.normalize(number), VehicleType.CAR);
        vehicle.setModel("Axio");
        ReflectionTestUtils.setField(vehicle, "version", 0L);
        return vehicle;
    }
}
