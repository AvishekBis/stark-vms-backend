package com.starkgroup.vms.driver;

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
import com.starkgroup.vms.driver.dto.DriverRequest;
import com.starkgroup.vms.driver.dto.DriverResponse;
import com.starkgroup.vms.driver.dto.UpdateDriverRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * HTTP mapping only; every rule lives in {@link DriverService}.
 */
@RestController
@RequestMapping(DriverController.BASE_PATH)
@Tag(name = "Drivers", description = "Driver management (FR-DR01, FR-X02)")
public class DriverController {

    static final String BASE_PATH = "/api/v1/drivers";

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping
    @Operation(summary = "List drivers (search, status, available, paged, sorted)")
    public PageResponse<DriverResponse> list(
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater.") int page,
            @Parameter(description = "Page size, default 20, capped at 100")
            @RequestParam(defaultValue = "" + PageRequests.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "Size must be 1 or greater.") int size,
            @Parameter(description = "field,direction - fields: fullName, licenseExpiry, createdAt",
                    example = "fullName,asc")
            @RequestParam(defaultValue = "fullName,asc") String sort,
            @Parameter(description = "Case-insensitive partial match on full name, phone or licence number",
                    example = "rahim")
            @RequestParam(required = false) String search,
            @Parameter(description = "Only drivers with this status")
            @RequestParam(required = false) DriverStatus status,
            @Parameter(description = "true = only ACTIVE drivers without a current vehicle (assign dialog)")
            @RequestParam(required = false) Boolean available) {
        return driverService.findAll(search, status, available, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one driver incl. licence status and current vehicle")
    public DriverResponse get(@PathVariable Long id) {
        return driverService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a driver (duplicate phone or licence number returns 409)")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "URL of the new driver"))
    @ApiResponse(responseCode = "400", description = "Validation failed (field errors)")
    @ApiResponse(responseCode = "409", description = "duplicate-driver-phone or duplicate-driver-license")
    public ResponseEntity<DriverResponse> create(@Valid @RequestBody DriverRequest request) {
        DriverResponse created = driverService.create(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a driver (status INACTIVE also closes the current assignment)",
            description = "version is required; a different version than the stored one returns 409 stale-version.")
    @ApiResponse(responseCode = "200", description = "The updated driver")
    @ApiResponse(responseCode = "400", description = "Validation failed (field errors)")
    @ApiResponse(responseCode = "404", description = "not-found")
    @ApiResponse(responseCode = "409",
            description = "stale-version, duplicate-driver-phone or duplicate-driver-license")
    public DriverResponse update(@PathVariable Long id, @Valid @RequestBody UpdateDriverRequest request) {
        return driverService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a driver without assignment history (ADMIN only)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "STAFF is not allowed")
    @ApiResponse(responseCode = "409", description = "driver-has-history - set the driver INACTIVE instead")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        driverService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
