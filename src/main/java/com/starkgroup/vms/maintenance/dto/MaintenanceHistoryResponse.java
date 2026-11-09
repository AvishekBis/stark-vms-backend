package com.starkgroup.vms.maintenance.dto;

import java.math.BigDecimal;
import java.util.List;

import com.starkgroup.vms.common.dto.PageResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-M02: one page of a vehicle's maintenance history plus totals over <b>all</b> its records.
 */
public record MaintenanceHistoryResponse(
        List<MaintenanceResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        @Schema(example = "12500.0", description = "Sum over all records of the vehicle, not just this page")
        BigDecimal totalCost,
        @Schema(example = "7", description = "Number of records (same as totalElements)")
        long recordCount) {

    public static MaintenanceHistoryResponse of(PageResponse<MaintenanceResponse> page, BigDecimal totalCost) {
        return new MaintenanceHistoryResponse(page.content(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), totalCost, page.totalElements());
    }
}
