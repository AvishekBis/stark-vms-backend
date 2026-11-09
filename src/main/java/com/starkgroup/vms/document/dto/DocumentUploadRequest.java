package com.starkgroup.vms.document.dto;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import com.starkgroup.vms.document.DocumentType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * multipart/form-data parts of POST /api/v1/vehicles/{id}/documents (FR-D01, BR-07). "Expiry on or after issue" is
 * checked here (field error on expiryDate), again in the service and by a DB CHECK; file type and size are checked
 * in the service.
 */
@ExpiryOnOrAfterIssue
public record DocumentUploadRequest(
        @Schema(description = "PDF, JPG or PNG, at most 5 MB", type = "string", format = "binary")
        @NotNull(message = "A file is required.")
        MultipartFile file,

        @Schema(example = "TAX_TOKEN")
        @NotNull(message = "Document type is required.")
        DocumentType documentType,

        @Schema(example = "TT-2026-0099")
        @Size(max = 60, message = "Document number must be at most 60 characters.")
        String documentNumber,

        @Schema(example = "2025-12-01", type = "string", format = "date")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate issueDate,

        @Schema(example = "2026-11-30", type = "string", format = "date")
        @NotNull(message = "Expiry date is required.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate expiryDate) {
}
