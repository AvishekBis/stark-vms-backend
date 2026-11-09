package com.starkgroup.vms.document.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.starkgroup.vms.common.expiry.ExpiryStatus;
import com.starkgroup.vms.document.DocumentType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Document metadata with the computed expiry state (BR-08, contract 6.5).
 */
public record DocumentResponse(
        @Schema(example = "31") Long id,
        @Schema(example = "12") Long vehicleId,
        @Schema(example = "DHK-1234") String vehicleNumber,
        @Schema(example = "TAX_TOKEN") DocumentType documentType,
        @Schema(example = "TT-2026-0099", nullable = true) String documentNumber,
        @Schema(example = "2025-12-01", nullable = true) LocalDate issueDate,
        @Schema(example = "2026-11-30") LocalDate expiryDate,
        @Schema(example = "EXPIRING_SOON") ExpiryStatus expiryStatus,
        @Schema(example = "12") int daysUntilExpiry,
        @Schema(example = "tax-token.pdf") String fileName,
        @Schema(example = "application/pdf") String contentType,
        @Schema(example = "182344") long fileSize,
        Instant createdAt,
        @Schema(example = "staff@stark.local") String createdBy) {
}
