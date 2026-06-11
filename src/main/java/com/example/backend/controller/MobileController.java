package com.example.backend.controller;

import com.example.backend.dto.ConsultationCaseDTO;
import com.example.backend.dto.MobileLoginRequestDTO;
import com.example.backend.dto.MobileLoginResponseDTO;
import com.example.backend.dto.MobileNotificationDTO;
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
import java.util.Map;

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

    /**
     * Catálogo de políticas. El correo + CI son opcionales: si el cliente
     * ya está registrado, el formulario inicial vuelve sin los campos
     * reservados cliente_* (no le pedimos datos que ya tenemos).
     */
    @GetMapping("/policies")
    public ResponseEntity<List<MobilePolicyDTO>> listPolicies(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String ci) {
        return ResponseEntity.ok(mobilePortalService.listPolicies(email, ci));
    }

    @GetMapping("/policies/{policyId}")
    public ResponseEntity<MobilePolicyDTO> getPolicy(
            @PathVariable String policyId,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String ci) {
        return ResponseEntity.ok(mobilePortalService.getPolicy(policyId, email, ci));
    }

    @PostMapping("/start-case")
    public ResponseEntity<StartCaseResponseDTO> startCase(
            @Valid @RequestBody MobileStartCaseRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mobilePortalService.startCase(request));
    }

    // ── Notificaciones push ───────────────────────────────────────────────

    /** Registra el token FCM del dispositivo del cliente (upsert). */
    @PostMapping("/device-token")
    public ResponseEntity<Void> registerDevice(@RequestBody Map<String, String> body) {
        mobilePortalService.registerDevice(
                body.get("email"), body.get("ci"),
                body.get("token"), body.get("platform"));
        return ResponseEntity.noContent().build();
    }

    /** Últimas notificaciones del cliente (campanita / historial). */
    @GetMapping("/notifications")
    public ResponseEntity<List<MobileNotificationDTO>> getNotifications(
            @RequestParam String email,
            @RequestParam String ci) {
        return ResponseEntity.ok(mobilePortalService.getNotifications(email, ci));
    }

    /** Marca todas las notificaciones del cliente como leídas. */
    @PostMapping("/notifications/mark-read")
    public ResponseEntity<Void> markNotificationsRead(@RequestBody Map<String, String> body) {
        mobilePortalService.markNotificationsRead(body.get("email"), body.get("ci"));
        return ResponseEntity.noContent().build();
    }
}
