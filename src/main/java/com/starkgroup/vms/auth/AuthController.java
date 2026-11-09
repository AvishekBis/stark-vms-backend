package com.starkgroup.vms.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starkgroup.vms.auth.dto.LoginRequest;
import com.starkgroup.vms.auth.dto.LoginResponse;
import com.starkgroup.vms.auth.dto.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login and current user (FR-AUTH-01, FR-AUTH-02, ADR 0022)")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * The client IP is the request's remote address. With server.forward-headers-strategy=native, Tomcat's
     * RemoteIpValve sets it only for requests from a trusted (internal) proxy, to the right-most X-Forwarded-For
     * entry that is not such a proxy - the address Render's proxy appended. Client-supplied left-most entries and
     * the RFC 7239 Forwarded header are ignored (ADR 0022, review finding SEC-1). The service never sees the
     * servlet request.
     */
    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in with email and password and receive a JWT",
            description = "Wrong email or password returns 401 with a generic message. After 5 failed logins for an "
                    + "e-mail (or 20 from one IP) within 15 minutes, login answers 429 for 15 minutes.")
    @ApiResponse(responseCode = "200", description = "Bearer access token and the logged-in user")
    @ApiResponse(responseCode = "400", description = "Validation failed (email or password missing)")
    @ApiResponse(responseCode = "401", description = "Invalid email or password (generic message)")
    @ApiResponse(responseCode = "429", description = "too-many-login-attempts",
            headers = @Header(name = "Retry-After", description = "Seconds until the lock ends"))
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    @GetMapping("/me")
    @Operation(summary = "Current user and role")
    @ApiResponse(responseCode = "200", description = "The logged-in user")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired token")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.currentUser(principal);
    }
}
