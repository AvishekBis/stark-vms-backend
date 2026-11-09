package com.starkgroup.vms.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starkgroup.vms.dashboard.dto.DashboardSummaryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * HTTP mapping only; every number is computed in {@link DashboardService}.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Dashboard and summary (FR-DB01, FR-X02)")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @Operation(summary = "All dashboard numbers in one call",
            description = "Vehicles by status, documents expired / expiring within 30 days, maintenance count and "
                    + "cost this month (Asia/Dhaka), driver and licence counts, and the top-10 expiring lists.")
    public DashboardSummaryResponse summary() {
        return dashboardService.summary();
    }
}
