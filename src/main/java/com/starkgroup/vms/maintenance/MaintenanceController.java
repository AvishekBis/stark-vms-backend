package com.starkgroup.vms.maintenance;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.maintenance.dto.MaintenanceHistoryResponse;
import com.starkgroup.vms.maintenance.dto.MaintenanceRequest;
import com.starkgroup.vms.maintenance.dto.MaintenanceResponse;
import com.starkgroup.vms.maintenance.dto.UpdateMaintenanceRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * HTTP mapping only; every rule lives in {@link MaintenanceService}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Maintenance", description = "Maintenance records and history (FR-M01, FR-M02, BR-09)")
public class MaintenanceController {

    static final String MAINTENANCE_PATH = "/api/v1/maintenance";

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping("/vehicles/{vehicleId}/maintenance")
    @Operation(summary = "Maintenance history of a vehicle, newest first, with totalCost over all records")
    public MaintenanceHistoryResponse history(
            @PathVariable Long vehicleId,
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater.") int page,
            @Parameter(description = "Page size, default 20, capped at 100")
            @RequestParam(defaultValue = "" + PageRequests.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "Size must be 1 or greater.") int size) {
        return maintenanceService.history(vehicleId, page, size);
    }

    @PostMapping("/vehicles/{vehicleId}/maintenance")
    @Operation(summary = "Add a maintenance record (service date not in the future, cost >= 0)")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "URL of the new maintenance record"))
    @ApiResponse(responseCode = "400", description = "Validation failed (e.g. serviceDate in the future, cost < 0)")
    public ResponseEntity<MaintenanceResponse> create(@PathVariable Long vehicleId,
                                                      @Valid @RequestBody MaintenanceRequest request) {
        MaintenanceResponse created = maintenanceService.create(vehicleId, request);
        return ResponseEntity.created(URI.create(MAINTENANCE_PATH + "/" + created.id())).body(created);
    }

    @GetMapping("/maintenance/{recordId}")
    @Operation(summary = "Get one maintenance record")
    public MaintenanceResponse get(@PathVariable Long recordId) {
        return maintenanceService.findById(recordId);
    }

    @PutMapping("/maintenance/{recordId}")
    @Operation(summary = "Edit a maintenance record",
            description = "version is required; a different version than the stored one returns 409 stale-version.")
    @ApiResponse(responseCode = "200", description = "The updated record")
    @ApiResponse(responseCode = "400", description = "Validation failed (e.g. serviceDate in the future, cost < 0)")
    @ApiResponse(responseCode = "404", description = "not-found")
    @ApiResponse(responseCode = "409", description = "stale-version")
    public MaintenanceResponse update(@PathVariable Long recordId,
                                      @Valid @RequestBody UpdateMaintenanceRequest request) {
        return maintenanceService.update(recordId, request);
    }

    @DeleteMapping("/maintenance/{recordId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a maintenance record (ADMIN only)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "STAFF is not allowed")
    public ResponseEntity<Void> delete(@PathVariable Long recordId) {
        maintenanceService.delete(recordId);
        return ResponseEntity.noContent().build();
    }
}
