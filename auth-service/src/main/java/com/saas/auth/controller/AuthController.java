package com.saas.auth.controller;

import com.saas.auth.dto.*;
import com.saas.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Auth endpoints")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new user")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(201).build();
    }

    /** 0 tenants -> empty list; 1 -> a direct token; &gt;1 -> tenant list + loginSessionId (REQ-AUTH-01/02/03). */
    @PostMapping("/login")
    @Operation(summary = "Login; returns a token directly, or a tenant choice for multi-tenant users")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        LoginOutcome outcome = authService.login(request);
        if (outcome.token() != null) {
            return ResponseEntity.ok(outcome.token());
        }
        if (outcome.loginSessionId() != null) {
            return ResponseEntity.ok(Map.of("tenants", outcome.tenants(), "loginSessionId", outcome.loginSessionId()));
        }
        return ResponseEntity.ok(Map.of("tenants", outcome.tenants()));
    }

    @PostMapping("/login/tenant")
    @Operation(summary = "Exchange a loginSessionId + chosen tenantId for the real token")
    public ResponseEntity<AuthResponse> selectTenant(@Valid @RequestBody SelectTenantRequest request) {
        return ResponseEntity.ok(authService.selectTenant(request.getLoginSessionId(), request.getTenantId()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request password reset")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        authService.forgotPassword(request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password with token")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }
}
