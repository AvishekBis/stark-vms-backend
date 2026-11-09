package com.starkgroup.vms.document;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.exception.FieldValidationException;
import com.starkgroup.vms.common.exception.NotFoundException;
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.common.util.TextValues;
import com.starkgroup.vms.document.dto.DocumentFile;
import com.starkgroup.vms.document.dto.DocumentResponse;
import com.starkgroup.vms.document.dto.DocumentUploadRequest;
import com.starkgroup.vms.document.storage.StorageService;
import com.starkgroup.vms.vehicle.Vehicle;
import com.starkgroup.vms.vehicle.VehicleRepository;

/**
 * Vehicle documents (FR-D01, FR-D02, BR-07, BR-08): upload with file checks, list, download, delete and the
 * expiring-documents list.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String RESOURCE_NAME = "Document";
    static final String EXPIRY_DATE_FIELD = "expiryDate";
    static final String EXPIRY_BEFORE_ISSUE = "Expiry date must be on or after the issue date.";
    private static final Sort SOONEST_FIRST = Sort.by(Sort.Order.asc("expiryDate"), Sort.Order.asc("id"));

    private final VehicleDocumentRepository documentRepository;
    private final VehicleRepository vehicleRepository;
    private final StorageService storageService;
    private final FileValidator fileValidator;
    private final DocumentMapper mapper;
    private final ExpiryCalculator expiryCalculator;

    public DocumentService(VehicleDocumentRepository documentRepository, VehicleRepository vehicleRepository,
                           StorageService storageService, FileValidator fileValidator, DocumentMapper mapper,
                           ExpiryCalculator expiryCalculator) {
        this.documentRepository = documentRepository;
        this.vehicleRepository = vehicleRepository;
        this.storageService = storageService;
        this.fileValidator = fileValidator;
        this.mapper = mapper;
        this.expiryCalculator = expiryCalculator;
    }

    /** All documents of a vehicle, soonest expiry first (not paged - a vehicle has few documents). */
    @Transactional(readOnly = true)
    public List<DocumentResponse> findByVehicle(Long vehicleId) {
        requireVehicle(vehicleId);
        return documentRepository.findByVehicleIdOrderByExpiryDateAscIdAsc(vehicleId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse findById(Long documentId) {
        return mapper.toResponse(getDocument(documentId));
    }

    /**
     * Validates the file (BR-07) and dates, stores the bytes under a random UUID key and saves the metadata.
     * If saving the metadata fails after the bytes were stored externally, the stored object is removed again.
     */
    @Transactional
    public DocumentResponse upload(Long vehicleId, DocumentUploadRequest request) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Vehicle", vehicleId));
        FileValidator.ValidatedFile file = fileValidator.validate(request.file());
        requireExpiryOnOrAfterIssue(request.issueDate(), request.expiryDate());
        String storageKey = UUID.randomUUID().toString();
        storageService.store(storageKey, file.content(), file.contentType());
        try {
            VehicleDocument document = new VehicleDocument(vehicle, request.documentType(),
                    TextValues.trimToNull(request.documentNumber()), request.issueDate(), request.expiryDate(),
                    storageKey, file.fileName(), file.contentType(), file.size());
            return mapper.toResponse(documentRepository.saveAndFlush(document));
        } catch (RuntimeException ex) {
            deleteQuietly(storageKey);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public DocumentFile loadFile(Long documentId) {
        VehicleDocument document = getDocument(documentId);
        return new DocumentFile(document.getFileName(), document.getContentType(),
                storageService.load(document.getStorageKey()));
    }

    /** Deletes the metadata and the stored file. */
    @Transactional
    public void delete(Long documentId) {
        VehicleDocument document = getDocument(documentId);
        documentRepository.delete(document);
        documentRepository.flush();
        storageService.delete(document.getStorageKey());
    }

    /**
     * Documents with {@code expiryDate <= today + withinDays} - already expired ones included - soonest first.
     */
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> findExpiring(int withinDays, int page, int size) {
        LocalDate until = expiryCalculator.today().plusDays(withinDays);
        PageRequest pageRequest = PageRequests.of(page, size, null, Map.of(), SOONEST_FIRST);
        return PageResponse.from(documentRepository.findByExpiryDateLessThanEqual(until, pageRequest)
                .map(mapper::toResponse));
    }

    private void requireExpiryOnOrAfterIssue(LocalDate issueDate, LocalDate expiryDate) {
        if (issueDate != null && expiryDate != null && expiryDate.isBefore(issueDate)) {
            throw new FieldValidationException(EXPIRY_DATE_FIELD, EXPIRY_BEFORE_ISSUE);
        }
    }

    private void requireVehicle(Long vehicleId) {
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new NotFoundException("Vehicle", vehicleId);
        }
    }

    private VehicleDocument getDocument(Long documentId) {
        return documentRepository.findWithVehicleById(documentId)
                .orElseThrow(() -> new NotFoundException(RESOURCE_NAME, documentId));
    }

    private void deleteQuietly(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not remove stored file {} after a failed upload.", storageKey, cleanupFailure);
        }
    }
}
