package com.starkgroup.vms.document;

import org.springframework.stereotype.Component;

import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.expiry.ExpiryInfo;
import com.starkgroup.vms.document.dto.DocumentResponse;

/**
 * Entity to DTO mapping; the expiry status is computed here from the expiry date (BR-08), never stored.
 * Call inside a transaction: the vehicle is a lazy association.
 */
@Component
public class DocumentMapper {

    private final ExpiryCalculator expiryCalculator;

    public DocumentMapper(ExpiryCalculator expiryCalculator) {
        this.expiryCalculator = expiryCalculator;
    }

    public DocumentResponse toResponse(VehicleDocument document) {
        ExpiryInfo expiry = expiryCalculator.evaluate(document.getExpiryDate());
        return new DocumentResponse(
                document.getId(),
                document.getVehicle().getId(),
                document.getVehicle().getVehicleNumber(),
                document.getDocumentType(),
                document.getDocumentNumber(),
                document.getIssueDate(),
                document.getExpiryDate(),
                expiry.status(),
                expiry.daysUntilExpiry(),
                document.getFileName(),
                document.getContentType(),
                document.getFileSize(),
                document.getCreatedAt(),
                document.getCreatedBy());
    }
}
