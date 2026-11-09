package com.starkgroup.vms.vehicle;

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

import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.vehicle.dto.CreateVehicleRequest;
import com.starkgroup.vms.vehicle.dto.UpdateVehicleRequest;
import com.starkgroup.vms.vehicle.dto.VehicleResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * HTTP mapping only; every rule lives in {@link VehicleService}.
 */
@RestController
@RequestMapping(VehicleController.BASE_PATH)
@Tag(name = "Vehicles", description = "Vehicle register (FR-V01..FR-V06)")
public class VehicleController {

    static final String BASE_PATH = "/api/v1/vehicles";

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    @Operation(summary = "List vehicles (paged, sorted, optional search on vehicle number)")
    public PageResponse<VehicleResponse> list(
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater.") int page,
            @Parameter(description = "Page size, default 20, capped at 100")
            @RequestParam(defaultValue = "" + PageRequests.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "Size must be 1 or greater.") int size,
            @Parameter(description = "field,direction - fields: vehicleNumber, manufactureYear, createdAt",
                    example = "createdAt,desc")
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @Parameter(description = "Case-insensitive partial match on vehicle number", example = "DHK")
            @RequestParam(required = false) String search) {
        return vehicleService.findAll(search, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one vehicle")
    public VehicleResponse get(@PathVariable Long id) {
        return vehicleService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Register a vehicle (duplicate numbers return 409)")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "URL of the new vehicle"))
    @ApiResponse(responseCode = "400", description = "Validation failed (field errors)")
    @ApiResponse(responseCode = "409", description = "duplicate-vehicle-number (field vehicleNumber)")
    public ResponseEntity<VehicleResponse> create(@Valid @RequestBody CreateVehicleRequest request) {
        VehicleResponse created = vehicleService.create(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a vehicle (keeping its own number is allowed)")
    @ApiResponse(responseCode = "200", description = "The updated vehicle")
    @ApiResponse(responseCode = "400", description = "Validation failed (field errors)")
    @ApiResponse(responseCode = "404", description = "not-found")
    @ApiResponse(responseCode = "409", description = "duplicate-vehicle-number (field vehicleNumber)")
    public VehicleResponse update(@PathVariable Long id, @Valid @RequestBody UpdateVehicleRequest request) {
        return vehicleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a vehicle (ADMIN only)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "STAFF is not allowed")
    @ApiResponse(responseCode = "404", description = "not-found")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vehicleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
