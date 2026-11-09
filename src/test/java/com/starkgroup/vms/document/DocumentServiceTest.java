package com.starkgroup.vms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.starkgroup.vms.common.exception.FieldValidationException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.expiry.ExpiryStatus;
import com.starkgroup.vms.document.dto.DocumentResponse;
import com.starkgroup.vms.document.dto.DocumentUploadRequest;
import com.starkgroup.vms.document.storage.StorageService;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleRepository;
import com.starkgroup.vms.vehicle.VehicleType;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T04:00:00Z"), ZoneId.of("Asia/Dhaka"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    @Mock
    private VehicleDocumentRepository documentRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private StorageService storageService;

    private DocumentService documentService;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        ExpiryCalculator expiryCalculator = new ExpiryCalculator(CLOCK);
        documentService = new DocumentService(documentRepository, vehicleRepository, storageService,
                new FileValidator(), new DocumentMapper(expiryCalculator), expiryCalculator);
        vehicle = new Vehicle("DHK-1234", "DHK1234", VehicleType.CAR);
        ReflectionTestUtils.setField(vehicle, "id", 12L);
    }

    @Test
    void frD01_uploadStoresUnderRandomKeyAndKeepsOriginalNameAsMetadata() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));
        when(documentRepository.saveAndFlush(any(VehicleDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DocumentResponse response = documentService.upload(12L,
                upload("../secret/tax-token.pdf", TODAY.minusYears(1), TODAY.plusDays(10)));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storageService).store(key.capture(), eq(FileValidatorTest.PDF), eq("application/pdf"));
        assertThat(UUID.fromString(key.getValue())).isNotNull();
        assertThat(key.getValue()).doesNotContain("tax-token");
        assertThat(response.fileName()).isEqualTo("tax-token.pdf");
        assertThat(response.vehicleNumber()).isEqualTo("DHK-1234");
        assertThat(response.documentNumber()).isEqualTo("TT-2026-0099");
        assertThat(response.fileSize()).isEqualTo(FileValidatorTest.PDF.length);
        // TC-08: expires in 10 days.
        assertThat(response.expiryStatus()).isEqualTo(ExpiryStatus.EXPIRING_SOON);
        assertThat(response.daysUntilExpiry()).isEqualTo(10);
    }

    @Test
    void br07_expiryBeforeIssueDateIsRejectedBeforeAnythingIsStored() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> documentService.upload(12L, upload("a.pdf", TODAY, TODAY.minusDays(1))))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("expiryDate");
                    assertThat(ex.getMessage()).isEqualTo("Expiry date must be on or after the issue date.");
                });
        verify(storageService, never()).store(anyString(), any(), anyString());
    }

    @Test
    void br07_expiryEqualToIssueDateIsAllowed() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));
        when(documentRepository.saveAndFlush(any(VehicleDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(documentService.upload(12L, upload("a.pdf", TODAY, TODAY)).expiryStatus())
                .isEqualTo(ExpiryStatus.EXPIRING_SOON);
    }

    @Test
    void storedFileIsRemovedAgainWhenSavingTheMetadataFails() {
        when(vehicleRepository.findById(12L)).thenReturn(Optional.of(vehicle));
        when(documentRepository.saveAndFlush(any(VehicleDocument.class)))
                .thenThrow(new DataIntegrityViolationException("check constraint"));

        assertThatThrownBy(() -> documentService.upload(12L, upload("a.pdf", null, TODAY)))
                .isInstanceOf(DataIntegrityViolationException.class);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(storageService).store(stored.capture(), any(), anyString());
        verify(storageService).delete(stored.getValue());
    }

    @Test
    void uploadForMissingVehicleIsNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.upload(99L, upload("a.pdf", null, TODAY)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void tc09_documentThatExpiredYesterdayIsExpired() {
        VehicleDocument document = document(TODAY.minusDays(1));
        when(documentRepository.findWithVehicleById(31L)).thenReturn(Optional.of(document));

        DocumentResponse response = documentService.findById(31L);

        assertThat(response.expiryStatus()).isEqualTo(ExpiryStatus.EXPIRED);
        assertThat(response.daysUntilExpiry()).isEqualTo(-1);
    }

    @Test
    void frD02_expiringListUsesTodayPlusWithinDaysAndSoonestFirst() {
        when(documentRepository.findByExpiryDateLessThanEqual(eq(TODAY.plusDays(30)), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(java.util.List.of(document(TODAY)),
                        invocation.getArgument(1), 1));

        assertThat(documentService.findExpiring(30, 0, 20).totalElements()).isEqualTo(1);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findByExpiryDateLessThanEqual(eq(TODAY.plusDays(30)), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("expiryDate").isAscending()).isTrue();
    }

    @Test
    void deleteRemovesMetadataAndStoredFile() {
        VehicleDocument document = document(TODAY);
        when(documentRepository.findWithVehicleById(31L)).thenReturn(Optional.of(document));

        documentService.delete(31L);

        verify(documentRepository).delete(document);
        verify(storageService).delete("key-31");
    }

    private static DocumentUploadRequest upload(String fileName, LocalDate issue, LocalDate expiry) {
        return new DocumentUploadRequest(
                new MockMultipartFile("file", fileName, "application/pdf", FileValidatorTest.PDF),
                DocumentType.TAX_TOKEN, " TT-2026-0099 ", issue, expiry);
    }

    private VehicleDocument document(LocalDate expiry) {
        VehicleDocument document = new VehicleDocument(vehicle, DocumentType.INSURANCE, null, null, expiry,
                "key-31", "insurance.pdf", "application/pdf", 100);
        ReflectionTestUtils.setField(document, "id", 31L);
        return document;
    }
}
