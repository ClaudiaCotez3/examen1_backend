package com.example.backend.controller;

import com.example.backend.dto.AuthUserDTO;
import com.example.backend.dto.LoginRequestDTO;
import com.example.backend.dto.LoginResponseDTO;
import com.example.backend.dto.RegisterRequestDTO;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.RoleName;
import com.example.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * POST /api/auth/login    — public: returns JWT + user info
 * POST /api/auth/register — ADMIN only: creates users managed by the org admin
 * GET  /api/auth/me       — authenticated: returns the caller's profile
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('" + RoleName.ADMIN + "')")
    public ResponseEntity<AuthUserDTO> register(@Valid @RequestBody RegisterRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthUserDTO> me(@AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.ok(authService.me(principal.getEmail()));
    }
}
