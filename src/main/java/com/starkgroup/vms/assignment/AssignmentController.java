package com.starkgroup.vms.assignment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starkgroup.vms.assignment.dto.AssignDriverRequest;
import com.starkgroup.vms.assignment.dto.AssignmentResponse;
import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.util.PageRequests;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * HTTP mapping only; every rule lives in {@link AssignmentService}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Driver assignments", description = "Driver-vehicle assignment and history (FR-DR02, BR-05, BR-06)")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @PutMapping("/vehicles/{vehicleId}/driver")
    @Operation(summary = "Assign or change the vehicle's driver",
            description = "Closes the vehicle's previous assignment and opens a new one in one transaction. "
                    + "Assigning the current driver again returns the existing assignment.")
    @ApiResponse(responseCode = "200", description = "The new (or unchanged) current assignment")
    @ApiResponse(responseCode = "409", description = "assignment-not-allowed (vehicle or driver not ACTIVE) or "
            + "driver-already-assigned (driver drives another vehicle, TC-10)")
    public AssignmentResponse assign(@PathVariable Long vehicleId, @Valid @RequestBody AssignDriverRequest request) {
        return assignmentService.assign(vehicleId, request.driverId());
    }

    @DeleteMapping("/vehicles/{vehicleId}/driver")
    @Operation(summary = "Unassign the vehicle's current driver (history is kept)")
    @ApiResponse(responseCode = "204", description = "Assignment closed")
    @ApiResponse(responseCode = "409", description = "no-current-driver")
    public ResponseEntity<Void> unassign(@PathVariable Long vehicleId) {
        assignmentService.unassign(vehicleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/vehicles/{vehicleId}/assignments")
    @Operation(summary = "Driver history of a vehicle, newest first")
    public PageResponse<AssignmentResponse> vehicleHistory(
            @PathVariable Long vehicleId,
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater.") int page,
            @Parameter(description = "Page size, default 20, capped at 100")
            @RequestParam(defaultValue = "" + PageRequests.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "Size must be 1 or greater.") int size) {
        return assignmentService.historyForVehicle(vehicleId, page, size);
    }

    @GetMapping("/drivers/{driverId}/assignments")
    @Operation(summary = "Vehicle history of a driver, newest first")
    public PageResponse<AssignmentResponse> driverHistory(
            @PathVariable Long driverId,
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater.") int page,
            @Parameter(description = "Page size, default 20, capped at 100")
            @RequestParam(defaultValue = "" + PageRequests.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "Size must be 1 or greater.") int size) {
        return assignmentService.historyForDriver(driverId, page, size);
    }
}
