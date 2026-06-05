package com.example.backend.controller;

import com.example.backend.dto.ConsultationCaseDTO;
import com.example.backend.dto.MobileLoginRequestDTO;
import com.example.backend.dto.MobileLoginResponseDTO;
import com.example.backend.dto.MobilePolicyDTO;
import com.example.backend.dto.MobileStartCaseRequestDTO;
import com.example.backend.dto.StartCaseResponseDTO;
import com.example.backend.service.MobilePortalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Portal móvil del cliente (app Flutter) — /api/mobile/**.
 *
 * permitAll en SecurityConfig: los clientes no tienen cuenta de usuario;
 * cada endpoint exige y valida el par correo + CI contra la colección de
 * clientes (Opción B) en {@link MobilePortalService#authenticate}, así
 * que nada se sirve sin credenciales correctas.
 *
 *   POST /api/mobile/login            → sesión + mis trámites
 *   GET  /api/mobile/cases            → refresco de mis trámites
 *   GET  /api/mobile/policies         → catálogo para iniciar trámite
 *   GET  /api/mobile/policies/{id}    → política + formulario inicial
 *   POST /api/mobile/start-case       → iniciar trámite desde la app (Módulo 3)
 */
@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileController {

    private final MobilePortalService mobilePortalService;

    @PostMapping("/login")
    public ResponseEntity<MobileLoginResponseDTO> login(
            @Valid @RequestBody MobileLoginRequestDTO request) {
        return ResponseEntity.ok(
                mobilePortalService.login(request.getEmail(), request.getCi()));
    }

    @GetMapping("/cases")
    public ResponseEntity<List<ConsultationCaseDTO>> getCases(
            @RequestParam String email,
            @RequestParam String ci) {
        return ResponseEntity.ok(mobilePortalService.getCases(email, ci));
    }

    @GetMapping("/policies")
    public ResponseEntity<List<MobilePolicyDTO>> listPolicies() {
        return ResponseEntity.ok(mobilePortalService.listPolicies());
    }

    @GetMapping("/policies/{policyId}")
    public ResponseEntity<MobilePolicyDTO> getPolicy(@PathVariable String policyId) {
        return ResponseEntity.ok(mobilePortalService.getPolicy(policyId));
    }

    @PostMapping("/start-case")
    public ResponseEntity<StartCaseResponseDTO> startCase(
            @Valid @RequestBody MobileStartCaseRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mobilePortalService.startCase(request));
    }
}
