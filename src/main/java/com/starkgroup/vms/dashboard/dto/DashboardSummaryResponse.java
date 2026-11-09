package com.starkgroup.vms.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.starkgroup.vms.document.dto.DocumentResponse;
import com.starkgroup.vms.driver.dto.DriverResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /api/v1/dashboard/summary (FR-DB01, FR-X02, contract 6.7) - every dashboard number in one call.
 */
public record DashboardSummaryResponse(
        Vehicles vehicles,
        Documents documents,
        Maintenance maintenance,
        Drivers drivers,
        @Schema(description = "Up to 10 expired or expiring-soon documents, soonest expiry first")
        List<DocumentResponse> expiringDocuments,
        @Schema(description = "Up to 10 expired or expiring-soon driver licences, soonest expiry first")
        List<DriverResponse> expiringLicenses,
        Instant generatedAt) {

    public record Vehicles(
            @Schema(example = "26") long total,
            @Schema(example = "18") long active,
            @Schema(example = "3") long inactive,
            @Schema(example = "5") long maintenance) {
    }

    public record Documents(
            @Schema(example = "2") long expired,
            @Schema(example = "4") long expiringSoon,
            @Schema(example = "30") int withinDays) {
    }

    public record Maintenance(
            @Schema(example = "2026-11", description = "Current calendar month in Asia/Dhaka") String month,
            @Schema(example = "7") long count,
            @Schema(example = "48250.0") BigDecimal totalCost) {
    }

    public record Drivers(
            @Schema(example = "12") long total,
            @Schema(example = "11") long active,
            @Schema(example = "9") long assigned,
            @Schema(example = "1") long licensesExpired,
            @Schema(example = "2") long licensesExpiringSoon) {
    }
}
