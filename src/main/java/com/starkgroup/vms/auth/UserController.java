package com.starkgroup.vms.auth;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starkgroup.vms.auth.dto.CreateUserRequest;
import com.starkgroup.vms.auth.dto.ResetPasswordRequest;
import com.starkgroup.vms.auth.dto.UpdateUserRequest;
import com.starkgroup.vms.auth.dto.UserAccountResponse;
import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.util.PageRequests;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * HTTP mapping only; every rule lives in {@link UserService}. ADMIN only (STAFF gets 403). There is no DELETE:
 * users are disabled instead (ADR 0024).
 */
@RestController
@RequestMapping(UserController.BASE_PATH)
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "User account management, ADMIN only (ADR 0024)")
public class UserController {

    static final String BASE_PATH = "/api/v1/users";

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users (search, role and enabled filters, paged, sorted)")
    @ApiResponse(responseCode = "200", description = "One page of users")
    @ApiResponse(responseCode = "400", description = "Bad role / enabled value or unknown sort field")
    @ApiResponse(responseCode = "403", description = "STAFF is not allowed")
    public PageResponse<UserAccountResponse> list(
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater.") int page,
            @Parameter(description = "Page size, default 20, capped at 100")
            @RequestParam(defaultValue = "" + PageRequests.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "Size must be 1 or greater.") int size,
            @Parameter(description = "field,direction - fields: email, fullName, createdAt", example = "email,asc")
            @RequestParam(defaultValue = "email,asc") String sort,
            @Parameter(description = "Case-insensitive partial match on e-mail or full name", example = "officer")
            @RequestParam(required = false) String search,
            @Parameter(description = "Only users with this role")
            @RequestParam(required = false) Role role,
            @Parameter(description = "Only enabled (true) or disabled (false) users")
            @RequestParam(required = false) Boolean enabled) {
        return userService.findAll(search, role, enabled, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one user")
    @ApiResponse(responseCode = "200", description = "The user")
    @ApiResponse(responseCode = "404", description = "not-found")
    public UserAccountResponse get(@PathVariable Long id) {
        return userService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a user (enabled; e-mail stored trimmed and lower-case)")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "URL of the new user"))
    @ApiResponse(responseCode = "400", description = "Validation failed (field errors, password policy)")
    @ApiResponse(responseCode = "409", description = "duplicate-email (field email)")
    public ResponseEntity<UserAccountResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserAccountResponse created = userService.create(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a user's name, role and enabled flag (the e-mail cannot be changed)")
    @ApiResponse(responseCode = "200", description = "The updated user")
    @ApiResponse(responseCode = "400", description = "Validation failed (e.g. missing version)")
    @ApiResponse(responseCode = "404", description = "not-found")
    @ApiResponse(responseCode = "409", description = "stale-version, self-modification or last-admin")
    public UserAccountResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                      @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(actor, id, request);
    }

    @PostMapping("/{id}/password")
    @Operation(summary = "Reset a user's password; their existing tokens stop working")
    @ApiResponse(responseCode = "204", description = "Password reset")
    @ApiResponse(responseCode = "400", description = "Password policy (field newPassword)")
    @ApiResponse(responseCode = "404", description = "not-found")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }
}
