package com.example.backend.controller;

import com.example.backend.dto.ClientCasesDTO;
import com.example.backend.dto.RepositoryOverviewDTO;
import com.example.backend.service.AdminRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repositorio Documental — admin module (Opción B).
 *
 * Mounted under /api/admin/** so SecurityConfig's existing
 * {@code hasRole("ADMIN")} rule applies without changes.
 *
 *   GET /api/admin/repository/clients?search=         → KPIs + client list
 *   GET /api/admin/repository/clients/{clientId}/cases → a client's trámites
 *
 * From the client's file the UI jumps to the existing Expediente screen
 * (/expediente/:caseFileId) — nothing is duplicated.
 */
@RestController
@RequestMapping("/api/admin/repository")
@RequiredArgsConstructor
public class AdminRepositoryController {

    private final AdminRepositoryService adminRepositoryService;

    @GetMapping("/clients")
    public ResponseEntity<RepositoryOverviewDTO> getClients(
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(adminRepositoryService.getOverview(search));
    }

    @GetMapping("/clients/{clientId}/cases")
    public ResponseEntity<ClientCasesDTO> getClientCases(@PathVariable String clientId) {
        return ResponseEntity.ok(adminRepositoryService.getClientCases(clientId));
    }
}
